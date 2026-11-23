# payment-service API

Simulated UPI, IMPS and NEFT payments to an external bank, run as a distributed Saga: the customer is debited in
banking-core, the simulated bank is called, and the payment is then settled or the debit is reversed. The state is
persisted so interrupted payments are finished by a recovery job.

- Gateway: `http://localhost:8080/api/v1/payments...`
- Direct: `http://localhost:8084/api/v1/payments...`
- Authentication: `Authorization: Bearer <customer access token>`. Role `CUSTOMER` only.

```bash
export BASE=http://localhost:8080
export TOKEN="<customer accessToken>"
export ACCOUNT_ID="3f2b8c1e-5a47-4d1b-9c3e-1a2b3c4d5e6f"
```

## Payment states

```
REQUESTED --ledger debit ok--> DEBITED --bank accepts--> SETTLED
    |                             |
    +--ledger rejects--> FAILED   +--bank declines / never processed--> REFUNDED (reversal posted)
```

| `status` | Meaning | Final? |
| --- | --- | --- |
| `REQUESTED` | recorded, ledger debit not confirmed yet (banking-core unreachable); the recovery job continues it | no |
| `DEBITED` | money debited, bank outcome unknown (timeout); the recovery job asks the bank and finishes it | no |
| `SETTLED` | the bank accepted | yes |
| `REFUNDED` | the bank declined or never processed it; the debit was reversed (`failureReason` explains) | yes |
| `FAILED` | the ledger rejected the debit (e.g. `INSUFFICIENT_FUNDS`); nothing was debited (`failureReason` holds the code) | yes |

**Simulated bank behaviour**, chosen by `beneficiaryRef`: contains `FAIL` (any case) → declined; contains `TIMEOUT` →
no answer (unknown outcome); anything else → accepted.

**Rail ceilings per payment**: `UPI` 100000, `IMPS` 500000, `NEFT` 1000000.

Payment response:

| Field | Type | Description |
| --- | --- | --- |
| `id` | uuid | payment id (also the reference sent to the bank and used for ledger idempotency) |
| `accountId` | uuid | the debited account |
| `rail` | string | `UPI`, `IMPS` or `NEFT` |
| `beneficiaryRef` | string | beneficiary identifier |
| `amount` | number | 2 decimals |
| `currency` | string | `INR` |
| `status` | string | see above |
| `failureReason` | string | only for `REFUNDED` and `FAILED` |
| `createdAt` | string | ISO-8601 UTC |

---

## 1. Create a payment

| | |
| --- | --- |
| Method / URL | `POST http://localhost:8080/api/v1/payments` |
| Direct URL | `POST http://localhost:8084/api/v1/payments` |

**Headers**

| Header | Value | Required |
| --- | --- | --- |
| `Authorization` | `Bearer <token>` | yes |
| `Content-Type` | `application/json` | yes |
| `Idempotency-Key` | unique string, 1-100 chars | yes |

**Request body**

| Field | Type | Rules |
| --- | --- | --- |
| `accountId` | uuid | required, your account (checked by banking-core) |
| `rail` | string | required, `UPI`, `IMPS` or `NEFT` |
| `beneficiaryRef` | string | required, not blank, max 100 chars |
| `amount` | number | required, minimum `0.01`, at most 2 decimals, within the rail ceiling |

**curl**

```bash
curl -i -X POST "$BASE/api/v1/payments" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 0c1d2e3f-4a5b-4c6d-8e7f-a8b9c0d1e2f3" \
  -d '{
    "accountId": "3f2b8c1e-5a47-4d1b-9c3e-1a2b3c4d5e6f",
    "rail": "UPI",
    "beneficiaryRef": "shop@upi",
    "amount": 250.00
  }'
```

**Success: `201 Created`**: the Saga reached a final state (`SETTLED`, `REFUNDED` or `FAILED`)

```
HTTP/1.1 201
Content-Type: application/json
X-Request-Id: 2b3c4d5e-6f7a-4b8c-9d0e-f1a2b3c4d5e6
```

Settled:

```json
{
  "id": "d0e1f2a3-b4c5-4d6e-a7f8-b9c0d1e2f3a4",
  "accountId": "3f2b8c1e-5a47-4d1b-9c3e-1a2b3c4d5e6f",
  "rail": "UPI",
  "beneficiaryRef": "shop@upi",
  "amount": 250.00,
  "currency": "INR",
  "status": "SETTLED",
  "createdAt": "2026-11-23T15:40:09.771204Z"
}
```

Declined by the bank (`"beneficiaryRef": "FAIL-account"`): the debit was reversed automatically

```json
{
  "id": "e1f2a3b4-c5d6-4e7f-b8a9-c0d1e2f3a4b5",
  "accountId": "3f2b8c1e-5a47-4d1b-9c3e-1a2b3c4d5e6f",
  "rail": "UPI",
  "beneficiaryRef": "FAIL-account",
  "amount": 250.00,
  "currency": "INR",
  "status": "REFUNDED",
  "failureReason": "Declined by beneficiary bank",
  "createdAt": "2026-11-23T15:41:30.120845Z"
}
```

Rejected by the ledger (for example insufficient funds): nothing was debited

```json
{
  "id": "f2a3b4c5-d6e7-4f80-89b0-d1e2f3a4b5c6",
  "accountId": "3f2b8c1e-5a47-4d1b-9c3e-1a2b3c4d5e6f",
  "rail": "UPI",
  "beneficiaryRef": "shop@upi",
  "amount": 999999.00,
  "currency": "INR",
  "status": "FAILED",
  "failureReason": "INSUFFICIENT_FUNDS",
  "createdAt": "2026-11-23T15:42:02.553017Z"
}
```

**Success: `202 Accepted`**: still in flight. The money may already be debited (`DEBITED`, bank timeout) or the
ledger could not be reached (`REQUESTED`). Poll `GET /api/v1/payments/{id}`; the recovery job finishes it.

```
HTTP/1.1 202
Content-Type: application/json
X-Request-Id: 3c4d5e6f-7a8b-4c9d-8e1f-a2b3c4d5e6f7
```

```json
{
  "id": "a3b4c5d6-e7f8-4091-9ac1-e2f3a4b5c6d7",
  "accountId": "3f2b8c1e-5a47-4d1b-9c3e-1a2b3c4d5e6f",
  "rail": "NEFT",
  "beneficiaryRef": "TIMEOUT-account",
  "amount": 300.00,
  "currency": "INR",
  "status": "DEBITED",
  "createdAt": "2026-11-23T15:43:15.004419Z"
}
```

**Success: `200 OK`** with header `Idempotent-Replayed: true`: the same `Idempotency-Key` and body were sent again; the stored
payment (in whatever state it is in now) is returned and nothing new happens.

```
HTTP/1.1 200
Content-Type: application/json
Idempotent-Replayed: true
X-Request-Id: 4d5e6f7a-8b9c-4d0e-9f2a-b3c4d5e6f7a8
```

**Errors**

`400 Bad Request`: header missing

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Idempotency-Key header (1-100 chars) is required",
  "instance": "/api/v1/payments",
  "code": "IDEMPOTENCY_KEY_REQUIRED"
}
```

`400 Bad Request`: validation

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Request validation failed",
  "instance": "/api/v1/payments",
  "code": "VALIDATION_FAILED",
  "errors": {
    "beneficiaryRef": "must not be blank",
    "amount": "must be greater than or equal to 0.01"
  }
}
```

`400 Bad Request` (no `code`, `"detail": "Failed to read request"`) for an unknown `rail` such as `SWIFT`.

`422 Unprocessable Entity`: rail ceiling

```json
{
  "type": "about:blank",
  "title": "Unprocessable Entity",
  "status": 422,
  "detail": "UPI payments are limited to 100000",
  "instance": "/api/v1/payments",
  "code": "RAIL_LIMIT_EXCEEDED"
}
```

`422 IDEMPOTENCY_KEY_REUSED`: `"detail": "Idempotency-Key was already used with a different request"`.

`401` / `403`: missing token / admin token.

Note: ledger rejections (`INSUFFICIENT_FUNDS`, not your account, frozen account) are **not** HTTP errors here: the payment is
recorded and returned with `status: FAILED` and the reason code in `failureReason`.

---

## 2. Get a payment

Use this to follow a payment that returned `202`.

| | |
| --- | --- |
| Method / URL | `GET http://localhost:8080/api/v1/payments/{paymentId}` |
| Direct URL | `GET http://localhost:8084/api/v1/payments/{paymentId}` |

Only the user who created the payment can read it.

**Headers**: `Authorization: Bearer <token>` (required).

**curl**

```bash
curl -i "$BASE/api/v1/payments/a3b4c5d6-e7f8-4091-9ac1-e2f3a4b5c6d7" \
  -H "Authorization: Bearer $TOKEN"
```

**Success: `200 OK`**: the same payment object after the recovery job has resolved the timeout

```
HTTP/1.1 200
Content-Type: application/json
X-Request-Id: 5e6f7a8b-9c0d-4e1f-a03b-c4d5e6f7a8b9
```

```json
{
  "id": "a3b4c5d6-e7f8-4091-9ac1-e2f3a4b5c6d7",
  "accountId": "3f2b8c1e-5a47-4d1b-9c3e-1a2b3c4d5e6f",
  "rail": "NEFT",
  "beneficiaryRef": "TIMEOUT-account",
  "amount": 300.00,
  "currency": "INR",
  "status": "REFUNDED",
  "failureReason": "Bank reported failure during recovery",
  "createdAt": "2026-11-23T15:43:15.004419Z"
}
```

**Errors**

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Payment not found",
  "instance": "/api/v1/payments/a3b4c5d6-e7f8-4091-9ac1-e2f3a4b5c6d7",
  "code": "NOT_FOUND"
}
```

(also returned for another user's payment).

---

## Events published

Via the outbox to Kafka topic `fincore.payment-events` (key = payment id): `PaymentInitiated`, `PaymentSettled`,
`PaymentRefunded`, `PaymentFailed`. The notification-service turns settled / refunded / failed into customer messages.

## Other endpoints on this service

Direct port `8084` only: `GET /api/v1/admin/audit`, `GET /api/v1/admin/outbox/...`, see
[admin-operations.md](admin-operations.md).
