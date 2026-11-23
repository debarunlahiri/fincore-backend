# Internal (service-to-service) APIs

Used only between FinCore services. **Never call these from a client and never route them through the gateway** (the
gateway answers `404` for any `/internal/**` path).

- Authentication: header `X-Internal-Key: <INTERNAL_API_KEY>` (default for local development
  `dev-internal-key-change-me`). User JWTs do **not** work here.
- Without the header, or with a wrong key: `401` (empty body). With a user JWT instead: `403` (empty body).
- Every mutating endpoint is **idempotent** so callers can retry safely after a timeout.
- Errors use the same problem format as the public API (status and `code` preserved when a caller relays them).

```bash
export KEY=dev-internal-key-change-me
export CUSTOMER_SVC=http://localhost:8082
export CORE=http://localhost:8083
```

| # | Endpoint | Served by | Called by |
| --- | --- | --- | --- |
| 1 | `GET /internal/customers/by-user/{userId}` | customer-service (8082) | banking-core (KYC check when opening an account) |
| 2 | `GET /internal/accounts/{accountId}/owner/{userId}` | banking-core (8083) | scheduler-service |
| 3 | `GET /internal/accounts/by-number/{accountNumber}` | banking-core (8083) | scheduler-service |
| 4 | `POST /internal/ledger/payment-debits` | banking-core (8083) | payment-service |
| 5 | `POST /internal/ledger/payment-reversals` | banking-core (8083) | payment-service |
| 6 | `POST /internal/transfers` | banking-core (8083) | scheduler-service |

Resilience on the callers: timeouts (1 s connect, 3-10 s read), up to 3 attempts with a short wait, and a circuit breaker
(opens at 50 % failures over the last 10 calls, stays open 10 s). Retries and the breaker apply only to network failures
and 5xx answers, never to business rejections such as `INSUFFICIENT_FUNDS`.

---

## 1. Customer summary by user id

| | |
| --- | --- |
| Method / URL | `GET http://localhost:8082/internal/customers/by-user/{userId}` |

**Headers**: `X-Internal-Key: <key>` (required).

**curl**

```bash
curl -i "$CUSTOMER_SVC/internal/customers/by-user/7c9e6679-7425-40de-944b-e07fc1f90ae7" \
  -H "X-Internal-Key: $KEY"
```

**Success: `200 OK`**

```
HTTP/1.1 200
Content-Type: application/json
```

```json
{
  "customerId": "0d3a5c1e-6f2b-4e8a-9b7d-4c1f2a3b5d6e",
  "userId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "kycStatus": "VERIFIED"
}
```

**Errors**: `404` with `"code": "PROFILE_NOT_FOUND"` (profile not created yet); `401` / `403` as above.

---

## 2. Check account ownership

| | |
| --- | --- |
| Method / URL | `GET http://localhost:8083/internal/accounts/{accountId}/owner/{userId}` |

**Headers**: `X-Internal-Key: <key>` (required).

**curl**

```bash
curl -i "$CORE/internal/accounts/3f2b8c1e-5a47-4d1b-9c3e-1a2b3c4d5e6f/owner/7c9e6679-7425-40de-944b-e07fc1f90ae7" \
  -H "X-Internal-Key: $KEY"
```

**Success: `204 No Content`**: the user owns the account (no body).

```
HTTP/1.1 204
```

**Errors**

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Account not found",
  "instance": "/internal/accounts/3f2b8c1e-5a47-4d1b-9c3e-1a2b3c4d5e6f/owner/11111111-2222-4333-8444-555555555555",
  "code": "NOT_FOUND"
}
```

(also when the account does not exist).

---

## 3. Resolve an account number to an id

| | |
| --- | --- |
| Method / URL | `GET http://localhost:8083/internal/accounts/by-number/{accountNumber}` |

**Headers**: `X-Internal-Key: <key>` (required).

**curl**

```bash
curl -i "$CORE/internal/accounts/by-number/FC1000000002" -H "X-Internal-Key: $KEY"
```

**Success: `200 OK`**

```
HTTP/1.1 200
Content-Type: application/json
```

```json
{
  "id": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"
}
```

**Errors**: `404 NOT_FOUND` (`"detail": "Account not found"`).

---

## 4. Debit an account for a payment (Saga step)

Debits the customer account and credits the settlement account in one ledger transaction. **Idempotent on `paymentId`**
(stored reference `PAY-DEBIT-<paymentId>`): repeating the call returns the original journal with `replayed: true` and
debits nothing more. Safe under concurrent duplicates.

| | |
| --- | --- |
| Method / URL | `POST http://localhost:8083/internal/ledger/payment-debits` |

**Headers**

| Header | Value | Required |
| --- | --- | --- |
| `X-Internal-Key` | the internal key | yes |
| `Content-Type` | `application/json` | yes |

**Request body**

| Field | Type | Rules |
| --- | --- | --- |
| `paymentId` | uuid | required, the idempotency reference |
| `userId` | uuid | required, must own `accountId` |
| `accountId` | uuid | required |
| `amount` | number | required, minimum `0.01`, at most 15 integer and 2 fraction digits |
| `description` | string | optional, max 200 chars (defaults to `Payment <paymentId>`) |

**curl**

```bash
curl -i -X POST "$CORE/internal/ledger/payment-debits" \
  -H "X-Internal-Key: $KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "paymentId": "d0e1f2a3-b4c5-4d6e-a7f8-b9c0d1e2f3a4",
    "userId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "accountId": "3f2b8c1e-5a47-4d1b-9c3e-1a2b3c4d5e6f",
    "amount": 250.00,
    "description": "UPI payment to shop@upi"
  }'
```

**Success: `200 OK`**

```
HTTP/1.1 200
Content-Type: application/json
```

```json
{
  "journalId": "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d",
  "replayed": false
}
```

A repeat of the same call returns `"replayed": true` with the same `journalId`.

**Errors** (the payment-service relays these with the same status and `code`)

| HTTP | `code` | Cause |
| --- | --- | --- |
| 404 | `NOT_FOUND` | account missing or not owned by `userId` |
| 422 | `INSUFFICIENT_FUNDS` | balance too low |
| 422 | `ACCOUNT_NOT_ACTIVE` | account frozen or closed |
| 400 | `VALIDATION_FAILED` | invalid body (`errors` object) |

---

## 5. Reverse a payment debit (Saga compensation)

Refunds a previous debit. The account and amount are taken from the **stored original debit**, never from the caller.
**Idempotent on `paymentId`** (stored reference `PAY-REV-<paymentId>`).

| | |
| --- | --- |
| Method / URL | `POST http://localhost:8083/internal/ledger/payment-reversals` |

**Headers**: `X-Internal-Key` (required), `Content-Type: application/json` (required).

**Request body**

| Field | Type | Rules |
| --- | --- | --- |
| `paymentId` | uuid | required, must have a recorded debit |

**curl**

```bash
curl -i -X POST "$CORE/internal/ledger/payment-reversals" \
  -H "X-Internal-Key: $KEY" \
  -H "Content-Type: application/json" \
  -d '{"paymentId":"d0e1f2a3-b4c5-4d6e-a7f8-b9c0d1e2f3a4"}'
```

**Success: `200 OK`**

```
HTTP/1.1 200
Content-Type: application/json
```

```json
{
  "journalId": "1b2c3d4e-5f6a-4b7c-9d8e-0f1a2b3c4d5e",
  "replayed": false
}
```

**Errors**

`404 Not Found`: no debit recorded for that payment

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "No debit was recorded for payment d0e1f2a3-b4c5-4d6e-a7f8-b9c0d1e2f3a4",
  "instance": "/internal/ledger/payment-reversals",
  "code": "NOT_FOUND"
}
```

`422 ACCOUNT_NOT_ACTIVE`: the account was frozen after the debit, so the refund cannot be posted until an admin
unfreezes it (the payment-service keeps retrying).

---

## 6. Execute a transfer on behalf of a user

Runs a transfer through exactly the same path as the public `POST /api/v1/transfers` (ownership, fraud rules, limits,
locking, ledger, outbox, idempotency). The caller supplies the idempotency key; the scheduler uses
`SI-<instructionId>-<runDate>`.

| | |
| --- | --- |
| Method / URL | `POST http://localhost:8083/internal/transfers` |

**Headers**: `X-Internal-Key` (required), `Content-Type: application/json` (required).

**Request body**

| Field | Type | Rules |
| --- | --- | --- |
| `userId` | uuid | required, the user the transfer is made for (must own the source account) |
| `idempotencyKey` | string | required, not blank |
| `request` | object | required, the public transfer body: `sourceAccountId`, `targetAccountNumber`, `amount`, optional `currency`, `remarks` (same rules as [banking-core.md](banking-core.md#8-transfer-money-account-to-account)) |

**curl**

```bash
curl -i -X POST "$CORE/internal/transfers" \
  -H "X-Internal-Key: $KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "idempotencyKey": "SI-1b2c3d4e-5f60-4a7b-8c9d-0e1f2a3b4c5d-2026-12-01",
    "request": {
      "sourceAccountId": "3f2b8c1e-5a47-4d1b-9c3e-1a2b3c4d5e6f",
      "targetAccountNumber": "FC1000000002",
      "amount": 5000.00,
      "currency": "INR",
      "remarks": "Rent"
    }
  }'
```

**Success: `200 OK`** (always `200`; `replayed` tells new from repeated)

```
HTTP/1.1 200
Content-Type: application/json
```

```json
{
  "transfer": {
    "id": "a7b8c9d0-e1f2-4a3b-b4c5-d6e7f8a9b0c1",
    "sourceAccountId": "3f2b8c1e-5a47-4d1b-9c3e-1a2b3c4d5e6f",
    "targetAccountId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
    "amount": 5000.00,
    "currency": "INR",
    "status": "COMPLETED",
    "remarks": "Rent",
    "journalId": "f6a7b8c9-d0e1-4f2a-83b4-c5d6e7f8a9b0",
    "createdAt": "2026-12-01T00:30:02.114873Z"
  },
  "replayed": false
}
```

**Errors**: the same as the public transfer (`404 NOT_FOUND`, `422 INSUFFICIENT_FUNDS`, `422 DAILY_LIMIT_EXCEEDED`,
`422 ACCOUNT_NOT_ACTIVE`, `422 FRAUD_REJECTED`, `422 IDEMPOTENCY_KEY_REUSED`, `400 VALIDATION_FAILED`, ...); see
[banking-core.md](banking-core.md).
