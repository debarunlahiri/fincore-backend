# FinCore API documentation

One file per service. Every endpoint lists the full URL, required headers, the request body with validation rules,
a ready-to-run `curl` command, the complete success response (status line, headers, body) and every error it can
return with the full body.

| Document | Service | Gateway path prefix | Direct port |
| --- | --- | --- | --- |
| [auth-service.md](auth-service.md) | auth-service | `/api/v1/auth` | 8081 |
| [customer-service.md](customer-service.md) | customer-service | `/api/v1/customers` | 8082 |
| [banking-core.md](banking-core.md) | banking-core (accounts, cash, statements, transfers, reconciliation, freeze) | `/api/v1/accounts`, `/api/v1/transfers`, `/api/v1/admin/reconciliation`, `/api/v1/admin/accounts` | 8083 |
| [payment-service.md](payment-service.md) | payment-service | `/api/v1/payments` | 8084 |
| [scheduler-service.md](scheduler-service.md) | scheduler-service (standing instructions) | `/api/v1/standing-instructions`, `/api/v1/admin/standing-instructions` | 8086 |
| *(no document)* | notification-service | none | 8085 | 

The notification-service has no public API: it only consumes Kafka events (`fincore.transfer-events`,
`fincore.payment-events`) and writes simulated SMS notifications. Its admin and actuator endpoints are in
[admin-operations.md](admin-operations.md).

| Document | Service | Gateway path prefix | Direct port |
| --- | --- | --- | --- |
| [admin-operations.md](admin-operations.md) | outbox dead-letter and audit endpoints present on every service | not routed | per service |
| [internal-apis.md](internal-apis.md) | service-to-service `/internal/**` | never routed | per service |

Interactive docs: every service also serves Swagger UI at `http://localhost:<port>/swagger-ui.html` and the
OpenAPI document at `http://localhost:<port>/v3/api-docs`.


## Identifier change: references and account numbers (read this first)

Customers never see internal ids any more. Wherever older sections of these documents show a UUID `id`,
`accountId`, `sourceAccountId` or `journalId`, use the public identifiers below. Details and the new endpoints
(payees, OTP, deposits, loans, cards, security) are in **[banking-features.md](banking-features.md)**.

| Endpoint | Before | Now |
| --- | --- | --- |
| `GET /api/v1/accounts`, `/{...}` | response `id`; path `/{id}` | no `id`; path `/{accountNumber}` |
| `/api/v1/accounts/{accountNumber}/deposits`, `withdrawals`, `statement`, `statement.csv` | path `{accountId}` | path `{accountNumber}` |
| deposit / withdrawal response | `journalId`, `accountId`, `balance` | `reference`, `accountNumber`, `balance` |
| statement line | `id`, `journalId` | `reference`, `description` |
| `POST /api/v1/transfers` | body `sourceAccountId` | body `sourceAccountNumber` |
| transfer response | `id`, `sourceAccountId`, `targetAccountId`, `journalId` | `reference`, `sourceAccountNumber`, `targetAccountNumber` |
| `GET /api/v1/transfers/{id}` | UUID | `{reference}` |
| `POST /api/v1/payments` | body `accountId` | body `accountNumber` |
| payment response, `GET /api/v1/payments/{id}` | `id`, `accountId` | `reference`, `accountNumber`; path `{reference}` |
| `POST /api/v1/standing-instructions` | body `sourceAccountId` | body `sourceAccountNumber` |
| instruction response, `/{id}/pause`, `resume`, `cancel` | `id`, `sourceAccountId` | `reference`, `sourceAccountNumber`; path `{reference}` |
| `POST /api/v1/admin/accounts/{id}/freeze`, `unfreeze` | account UUID | account number |
| `GET /api/v1/customers/me` | `id` | no `id` |
| `/api/v1/beneficiaries` | payees by account number, `DELETE /{accountNumber}` | payees of type `UPI`, `BANK`, `FINCORE`; `DELETE /{reference}` (`BEN...`) |
| `POST /api/v1/cards` | body `nameOnCard` | no `nameOnCard`: the holder name is taken from the customer profile |
| `POST /api/v1/auth/login` | any number of devices | one active device per user; 5 wrong passwords lock for 15 minutes |

The example payloads further down in `banking-core.md`, `payment-service.md`, `scheduler-service.md` and
`customer-service.md` have not been regenerated yet and still show the old field names.

## Base URLs

| Environment | Base URL | Notes |
| --- | --- | --- |
| Via the gateway (use this) | `http://localhost:9780` | single entry point, adds `X-Request-Id`, rate limiting |
| Directly to a service | `http://localhost:808x` | see the table above; needed for admin outbox/audit endpoints |

All examples use shell variables so they can be pasted as they are:

```bash
export BASE=http://localhost:9780
export TOKEN="<accessToken from register or login>"
export ADMIN_TOKEN="<accessToken of the admin user>"
```

`-i` is used in every curl command so the response status line and headers are shown.

## Authentication

| Caller | How | Header |
| --- | --- | --- |
| Customer / admin | JWT access token from `POST /api/v1/auth/login` (valid 15 minutes) | `Authorization: Bearer <accessToken>` |
| Another service | shared key, only on `/internal/**` | `X-Internal-Key: <INTERNAL_API_KEY>` |

Roles: `CUSTOMER` for customer endpoints and three staff roles for the admin endpoints. Customer and staff do not
overlap: a staff token is rejected on customer endpoints with `403`, and a customer token is rejected on admin
endpoints with `403`. `ADMIN` is the superuser; `OPERATIONS` changes state; `COMPLIANCE` is read-only oversight.

| Admin endpoint | ADMIN | OPERATIONS | COMPLIANCE |
| --- | :---: | :---: | :---: |
| `POST/GET /api/v1/admin/staff` (create / list staff) | yes | no | no |
| `POST /api/v1/admin/accounts/{id}/freeze` and `/unfreeze` | yes | yes | no |
| `POST /api/v1/admin/standing-instructions/run` | yes | yes | no |
| `POST /api/v1/admin/outbox/{id}/replay` | yes | yes | no |
| `GET /api/v1/admin/outbox/summary` and `/failed` | yes | yes | yes |
| `GET /api/v1/admin/reconciliation` | yes | no | yes |
| `GET /api/v1/admin/audit` | yes | no | yes |

A role without access gets `403 FORBIDDEN`. Staff users are created by an `ADMIN` with
[`POST /api/v1/admin/staff`](auth-service.md#5-create-a-staff-user-admin).
The default admin is `admin` / `ChangeMe!123` (override with `ADMIN_PASSWORD`).

Get an admin token:

```bash
curl -s -X POST "$BASE/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"ChangeMe!123"}'
```

## Standard headers

| Header | Direction | Where | Meaning |
| --- | --- | --- | --- |
| `Content-Type: application/json` | request | every request with a body | required |
| `Authorization: Bearer <jwt>` | request | every endpoint except `/api/v1/auth/**` and `/actuator/health` | identifies the caller |
| `Idempotency-Key: <1-100 chars>` | request | `POST /api/v1/transfers`, `POST /api/v1/payments` | required; makes retries safe |
| `X-Request-Id: <id>` | request / response | gateway | correlation id; generated if absent (kept if 64 characters or fewer) |
| `Idempotent-Replayed: true` | response | transfers, payments | the response is a replay of an earlier request with the same key |
| `Retry-After: 60` | response | gateway `429` | seconds to wait |
| `Content-Disposition: attachment; filename="statement-<account>-<from>-<to>.csv"` | response | CSV statement | download name |

## Error model

Every error is an RFC 7807 problem document with an extra machine-readable `code`:

```json
{
  "type": "about:blank",
  "title": "Unprocessable Entity",
  "status": 422,
  "detail": "Insufficient funds in account FC1000000001",
  "instance": "/api/v1/transfers",
  "code": "INSUFFICIENT_FUNDS"
}
```

Validation failures (`400`) add an `errors` object keyed by field:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Request validation failed",
  "instance": "/api/v1/auth/register",
  "code": "VALIDATION_FAILED",
  "errors": {
    "email": "must be a well-formed email address",
    "password": "size must be between 8 and 72"
  }
}
```

Exact `errors` messages come from Bean Validation and may differ slightly in wording.

Responses with no body: `401` (missing/invalid/expired token) is an empty body; `403` on `/internal/**` is an empty
body; `404` for a path that does not exist and `429` from the gateway have their own shapes (see below).
A request body that is not valid JSON, or has an unknown enum value, returns `400` with the standard problem
fields but no `code`:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Failed to read request",
  "instance": "/api/v1/accounts"
}
```

### Error code catalogue

| HTTP | `code` | Meaning |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | field validation failed; see `errors` |
| 400 | `IDEMPOTENCY_KEY_REQUIRED` | `Idempotency-Key` header missing, blank or longer than 100 characters |
| 400 | `SAME_ACCOUNT` | source and target account are the same |
| 400 | `INVALID_TARGET` | cannot transfer to a system account |
| 400 | `INVALID_ACCOUNT_TYPE` | `SYSTEM` account requested or targeted by an admin action |
| 400 | `INVALID_RANGE` | CSV statement date range invalid or longer than a year |
| 400 | `INVALID_ROLE` | staff creation with the `CUSTOMER` role |
| 400 | `INVALID_START_DATE` | standing instruction first run is in the past |
| 401 | `UNAUTHORIZED` | login failed, or refresh token invalid / expired / reused |
| 403 | `FORBIDDEN` | authenticated but the role is not allowed |
| 404 | `NOT_FOUND` | resource missing, or not owned by the caller (ids cannot be probed) |
| 404 | `PROFILE_NOT_FOUND` | customer profile not created yet (asynchronous after registration) |
| 409 | `USERNAME_TAKEN`, `EMAIL_TAKEN` | registration conflict |
| 409 | `KYC_ALREADY_VERIFIED` | KYC already verified |
| 409 | `ACCOUNT_CLOSED` | closed account cannot be changed |
| 409 | `INSTRUCTION_CANCELLED` | cancelled standing instruction cannot be changed |
| 409 | `CONCURRENT_UPDATE` | optimistic-lock conflict; retry |
| 422 | `INSUFFICIENT_FUNDS` | balance too low |
| 422 | `ACCOUNT_NOT_ACTIVE` | an account in the operation is frozen or closed |
| 422 | `DAILY_LIMIT_EXCEEDED` | daily transfer limit would be exceeded |
| 422 | `CURRENCY_MISMATCH` | only INR is supported |
| 422 | `FRAUD_REJECTED` | blocked by a fraud rule |
| 422 | `KYC_REQUIRED` | KYC not verified, cannot open an account |
| 422 | `IDEMPOTENCY_KEY_REUSED` | the key was already used with a different request |
| 422 | `RAIL_LIMIT_EXCEEDED` | payment amount above the rail ceiling |
| 429 | `RATE_LIMITED` | gateway rate limit (only when Redis limiting is enabled) |
| 500 | `INTERNAL_ERROR` | unexpected error; details are only in the server log |
| 503 | `DEPENDENCY_UNAVAILABLE` | a downstream service could not be reached |

### Gateway-specific responses

`404` for `/internal/**` (blocked at the gateway, empty body):

```
HTTP/1.1 404 Not Found
```

`429` when rate limited (`REDIS_ENABLED=true`):

```
HTTP/1.1 429 Too Many Requests
Retry-After: 60
Content-Type: application/problem+json
X-Request-Id: 5d0a5d9c-2a0f-4f43-8b87-6c2c3d1f0a11

{"status":429,"title":"Too Many Requests","detail":"Rate limit exceeded, retry later","code":"RATE_LIMITED"}
```

Limits: `/api/v1/auth/**` 10 requests per minute per client address; `POST /api/v1/transfers` and
`POST /api/v1/payments` 20 per minute per session.

## Conventions

- **Money**: JSON numbers with two decimals (`5000.00`), at most 15 integer digits and 2 fraction digits on input.
  Only `INR` is supported.
- **Ids**: UUIDs. **Account numbers**: `FC` followed by digits (`FC1000000001`).
- **Timestamps**: ISO-8601 UTC (`2026-11-23T15:04:05.123456Z`). **Dates**: `YYYY-MM-DD`.
- **Pagination**: `page` (0-based, default 0) and `size` (default 20, maximum 100) query parameters; responses use
  `{"content":[...],"page":0,"size":20,"totalElements":3,"totalPages":1}`.
- **Idempotency**: the same `Idempotency-Key` with the same body returns the original result (`200` with
  `Idempotent-Replayed: true`); the same key with a different body returns `422 IDEMPOTENCY_KEY_REUSED`. Keys are scoped
  to the calling user.
- Fields that are `null` are omitted from responses.

## End-to-end quick start

```bash
export BASE=http://localhost:9780

# 1. register (returns tokens) and keep the access token
RESP=$(curl -s -X POST "$BASE/api/v1/auth/register" -H "Content-Type: application/json" -d '{
  "username":"alice","email":"alice@example.com","password":"Passw0rd!23",
  "fullName":"Alice Example","phone":"9876543210","dateOfBirth":"1990-01-01"}')
export TOKEN=$(echo "$RESP" | python3 -c 'import sys,json; print(json.load(sys.stdin)["accessToken"])')

# 2. the profile is created asynchronously; retry this until it returns 200
curl -s "$BASE/api/v1/customers/me" -H "Authorization: Bearer $TOKEN"

# 3. KYC, then open an account and deposit
curl -s -X POST "$BASE/api/v1/customers/me/kyc" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"documentType":"PAN","documentNumber":"ABCDE1234F"}'
curl -s -X POST "$BASE/api/v1/accounts" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"type":"SAVINGS"}'
```

Continue with the per-service documents.
