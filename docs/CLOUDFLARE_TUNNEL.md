# Reaching this Mac from Vercel (Cloudflare Tunnel)

The Vercel-hosted web app proxies `/api/*` to `GATEWAY_URL` from Vercel's servers (see `next.config.mjs` in
fincore-web). A Cloudflare Tunnel gives the gateway on this Mac (`localhost:9780`) a public https address, with no
router changes: the Mac only makes an outbound connection.

```
browser -> Vercel (Next.js, /api rewrite) -> https://<tunnel address> -> Cloudflare -> cloudflared on this Mac -> gateway :9780
```

## Quick start (no account, random address)

```bash
scripts/tunnel.sh --up      # starts the backend (scripts/up.sh) if needed, then the tunnel
```

It prints an address like `https://something-random.trycloudflare.com`. In Vercel set `GATEWAY_URL` to it and
redeploy. The address changes every time the tunnel restarts, so this suits trying things out.

## Fixed address (recommended)

Needs a free Cloudflare account and a domain managed by Cloudflare.

1. Cloudflare Zero Trust > Networks > Tunnels > **Create a tunnel** > Cloudflared. Name it `fincore-mac`.
2. Copy the **token** from the connector command it shows (the long string after `--token`).
3. **Public Hostname** tab: add `api.yourdomain.com`, service type `HTTP`, URL `localhost:9780`.
4. On this Mac:
   ```bash
   cp .env.tunnel.example .env.tunnel     # then paste the token and the hostname into it
   scripts/tunnel.sh --up
   ```
5. Vercel > Settings > Environment Variables: `GATEWAY_URL = https://api.yourdomain.com`, then redeploy.
   Next.js reads it at **build** time, so a redeploy is required after any change.

## Good to know

- The Mac must stay on, awake (the script holds it awake with `caffeinate`) and online, with Docker running.
- Anyone who knows the address can call the gateway directly, not only your web app. The gateway's rate limits and
  JWT checks still apply, but do not publish the address. For more protection add a Cloudflare Access policy or
  WAF rule for the hostname.
- Rate limits use the client IP: the gateway trusts `X-Forwarded-For` only from private/loopback peers, which is what
  `cloudflared` is, so per-client limits keep working.
- `/actuator` health (port 9781) is bound to localhost and is not published.
- Keep real secrets (`JWT_SECRET`, `INTERNAL_API_KEY`) strong before exposing the backend. The development defaults
  are not safe on the internet.

## QR codes

Payment QRs open the web app, so the backend has to know its public address. Set it before starting the backend:

```bash
WEB_BASE_URL=https://your-app.vercel.app scripts/up.sh
```

Without it QRs point at `http://localhost:3000`, which only works on this Mac.
