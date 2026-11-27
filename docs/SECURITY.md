# Security

What protects the web app and the backend, what is configurable, and what is still your job in production.

## Layers

```
browser ──► Next.js (CSP, security headers) ──► api-gateway ──► services (JWT, roles, ownership checks)
                                                │
                                                ├─ request guard      bad methods, path tricks, oversized or non-JSON bodies
                                                ├─ rate limits        per address and per session, on by default
                                                ├─ security headers   nosniff, no-store, CSP default-src 'none', HSTS ...
                                                └─ /internal/** blocked, X-Internal-Key stripped from clients
```

## Rate limiting and DDoS protection

Everything below happens in the **api-gateway** before a request reaches any service.

| Limit | Default | Applies to | Stops |
| --- | --- | --- | --- |
| per address, per second | 30 | every request | floods and bursts |
| per address, per minute | 600 | every request | sustained flooding |
| `auth` | 10 / min per address | login, register, refresh, logout | password guessing, credential stuffing |
| `otp` | 5 / min per session | any `POST .../otp` | SMS bombing, code guessing |
| `money` | 20 / min per session | transfers, payments, deposits, withdrawals, loans, cards | runaway or abusive clients |
| `write` | 60 / min per session | every other state-changing call | spam |

Exceeding a limit returns `429` with `Retry-After`, `X-RateLimit-Limit` and the usual error body
(`code: RATE_LIMITED`). The web app turns that into "Too many requests. Please wait N seconds and try again."

Configure with `fincore.ratelimit.*` (see `api-gateway/src/main/resources/application.yml`). `RATELIMIT_ENABLED=false`
switches it off, which you should only do for a load test.

- **Store:** `memory` (default) counts inside each gateway instance and needs nothing else. `RATELIMIT_STORE=redis`
  shares the counts across instances (use it whenever more than one gateway runs). If Redis goes down the limiter falls
  back to memory, so the API stays up and stays protected. The memory table is capped, so rotating addresses cannot
  exhaust it.
- **Real client address:** `X-Forwarded-For` is only believed when the TCP peer is a trusted proxy
  (`TRUSTED_PROXIES`, default loopback and private ranges). The header is read from the right, skipping trusted hops, so
  an attacker cannot pick their own address by sending a fake one. **Set `TRUSTED_PROXIES` to your real ingress or load
  balancer ranges**, otherwise everyone behind your proxy shares one limit (or, if your proxy sits in a public range, the
  header is ignored).
- **Sessions:** the gateway does not parse JWTs, so a session key is a hash of the `Authorization` header. A client can
  forge that, so only the per-address limits are an attacker-proof control. The per-session ones keep honest users from
  harming each other.

Other flood controls: header size 16 KB, request line 4 KB, query string 4 KB, body 1 MB, connection and idle timeouts
(5 s / 30 s), a bounded connection pool to the services (500) and a 15 s upstream timeout. For a real DDoS (volumetric,
millions of requests) no application can cope on its own: put a CDN or WAF (Cloudflare, AWS Shield/WAF, ...) in front
and keep these limits as the second line.

## Request guard

The gateway refuses, before any work is done: methods other than GET, POST, PUT, DELETE, OPTIONS, HEAD (`405`); paths
with `..`, encoded dots, NUL, backslashes or `;` (`400`); bodies over 1 MB (`413`); POST/PUT bodies that are not JSON
(`415`); over-long query strings (`414`) and too many headers (`431`).

## Authentication and accounts

- Passwords: 8 to 72 characters with an upper case letter, a lower case letter and a digit, stored as bcrypt (cost 12).
- 5 wrong passwords lock the login for 15 minutes; every attempt is recorded in the login history.
- Access tokens last 5 minutes; refresh tokens rotate, and reusing an old one signs out every session.
- One active device per user: signing in elsewhere ends the old session.
- Sensitive actions need a one-time password that is bound to that exact action, expires in 5 minutes, works once and
  locks after 5 wrong tries. New payees also wait out a cooling period.
- Customers never see internal ids, only reference numbers and account numbers; other people's references return `404`.
- The web app signs the user out after 10 minutes of inactivity and keeps tokens in per-tab `sessionStorage`.

## Network exposure

- Only the gateway's port `9780` is published to the network. Every other port in `docker-compose.yml` (services,
  Postgres, Redis, Kafka, Jaeger, the gateway's health/metrics port `9781`) is bound to `127.0.0.1`.
- `/internal/**` is the service-to-service API: never routed, blocked at the gateway, and protected by the
  `X-Internal-Key` header, which the gateway strips from client requests.
- Kubernetes: `k8s/network-policy.yaml` denies all inbound traffic by default, allows the public to reach only the
  gateway, and lets the services talk only to each other.

## Web app headers

`next.config.mjs` sets a strict Content-Security-Policy (own origin only, no framing, no plugins), `X-Frame-Options`,
`X-Content-Type-Options`, `Referrer-Policy`, `Strict-Transport-Security`, `Permissions-Policy` and cross-origin policies,
and removes the `X-Powered-By` header. API responses are `no-store`.

## Secrets

Never run production with the repository defaults. With `SPRING_PROFILES_ACTIVE=prod` (set in `k8s/configmap.yaml`) every
service **refuses to start** if `JWT_SECRET` or `INTERNAL_API_KEY` is a development default or too short, or the admin
password is still the default. Generate real ones:

```bash
openssl rand -base64 48      # JWT_SECRET
openssl rand -hex 32         # INTERNAL_API_KEY
```

## What this does not cover (your checklist)

- TLS: terminate HTTPS at the ingress or load balancer. The HSTS header only has effect over HTTPS.
- A CDN/WAF for volumetric DDoS, and bot protection on registration and login (CAPTCHA) if you need it.
- Real SMS delivery for OTPs (set `OTP_EXPOSE_CODE=false`; today the code is only logged), real KYC, real banking rails.
- Dependency and image scanning in CI, secret scanning, regular patching, centralised log monitoring and alerting.
- JWT validation at the gateway (each service validates its own token), refresh tokens in an `httpOnly` cookie instead of
  `sessionStorage`, and a penetration test before handling real money.
- None of this was compiled or run yet: treat it as unverified until `mvn verify` and a manual test have passed.
