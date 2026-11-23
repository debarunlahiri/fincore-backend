# banking-core API

Accounts, cash deposits and withdrawals, statements, transfers, reconciliation and account freeze/unfreeze. This is the
service that owns the double-entry ledger.

- Gateway: `http://localhost:8080` (paths below)
- Direct: `http://localhost:8083` (same paths)
- Authentication: `Authorization: Bearer <access token>`.
  Sections 1-9 are **customer** endpoints (role `CUSTOMER`); sections 10-12 are **admin** endpoints (role `ADMIN`).
  A customer token on an admin endpoint, or an admin token on a customer endpoint, returns `403 FORBIDDEN`.

```bash
export BASE=http://localhost:8080
export TOKEN="<customer accessToken>"
export ADMIN_TOKEN="<admin accessToken>"
export ACCOUNT_ID="3f2b8c1e-5a47-4d1b-9c3e-1a2b3c4d5e6f"
```

Errors common to every endpoint:

`401 Unauthorized` (empty body): missing, malformed or expired token.

`403 Forbidden`: wrong role

```json
{
  "type": "about:blank",
  "title": "Forbidden",
  "status": 403,
  "detail": "You are not allowed to perform this action",
  "instance": "/api/v1/accounts",
  "code": "FORBIDDEN"
}
```

`404 Not Found`: an account that does not exist **or belongs to someone else** is reported identically, so account ids
cannot be probed

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Account not found",
  "instance": "/api/v1/accounts/3f2b8c1e-5a47-4d1b-9c3e-1a2b3c4d5e6f",
  "code": "NOT_FOUND"
}
```

Account response (accounts endpoints and freeze/unfreeze):

| Field | Type | Description |
| --- | --- | --- |
| `id` | uuid | account id |
| `accountNumber` | string | public number, e.g. `FC1000000001` |
| `type` | string | `SAVINGS` or `CURRENT` |
| `currency` | string | `INR` |
| `balance` | number | current balance, 2 decimals |
| `status` | string | `ACTIVE`, `FROZEN` or `CLOSED` |
| `dailyTransferLimit` | number | maximum total transferred out per day (default `100000.00`) |
| `createdAt` | string | ISO-8601 UTC |

---

## 1. Open an account

Requires verified KYC (checked against the customer-service).

| | |
| --- | --- |
| Method / URL | `POST http://localhost:8080/api/v1/accounts` |
| Direct URL | `POST http://localhost:8083/api/v1/accounts` |

**Headers**: `Authorization: Bearer <token>` (required), `Content-Type: application/json` (required).

**Request body**

| Field | Type | Rules |
| --- | --- | --- |
| `type` | string | required, `SAVINGS` or `CURRENT` (`SYSTEM` is rejected) |

**curl**

```bash
curl -i -X POST "$BASE/api/v1/accounts" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"type":"SAVINGS"}'
```

**Success: `201 Created`**

```
HTTP/1.1 201
Content-Type: application/json
X-Request-Id: 7a6b5c4d-3e2f-4a10-b9c8-d7e6f5a4b3c2
```

```json
{
  "id": "3f2b8c1e-5a47-4d1b-9c3e-1a2b3c4d5e6f",
  "accountNumber": "FC1000000001",
  "type": "SAVINGS",
  "currency": "INR",
  "balance": 0.00,
  "status": "ACTIVE",
  "dailyTransferLimit": 100000.00,
  "createdAt": "2026-11-23T15:04:05.123456Z"
}
```

**Errors**

`422 Unprocessable Entity`: KYC not verified

```json
{
  "type": "about:blank",
  "title": "Unprocessable Entity",
  "status": 422,
  "detail": "KYC must be verified before opening an account",
  "instance": "/api/v1/accounts",
  "code": "KYC_REQUIRED"
}
```

`400 Bad Request`: `{"type":"SYSTEM"}`

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "SYSTEM accounts cannot be opened by customers",
  "instance": "/api/v1/accounts",
  "code": "INVALID_ACCOUNT_TYPE"
}
```

`400 Bad Request`: `type` missing (`VALIDATION_FAILED`, `errors: {"type": "must not be null"}`) or an unknown value such as
`"SAVINGSS"` (`"detail": "Failed to read request"`, no `code`).

`404 Not Found` with `"code": "PROFILE_NOT_FOUND"`: the customer profile does not exist yet.

`503 Service Unavailable`: the customer-service could not be reached

```json
{
  "type": "about:blank",
  "title": "Service Unavailable",
  "status": 503,
  "detail": "Downstream service unreachable: Connection refused",
  "instance": "/api/v1/accounts",
  "code": "DEPENDENCY_UNAVAILABLE"
}
```

---

## 2. List my accounts

| | |
| --- | --- |
| Method / URL | `GET http://localhost:8080/api/v1/accounts` |
| Direct URL | `GET http://localhost:8083/api/v1/accounts` |

**Headers**: `Authorization: Bearer <token>` (required).

**curl**

```bash
curl -i "$BASE/api/v1/accounts" -H "Authorization: Bearer $TOKEN"
```

**Success: `200 OK`** (oldest first; `[]` when the customer has no accounts)

```
HTTP/1.1 200
Content-Type: application/json
X-Request-Id: 1c2d3e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f
```

```json
[
  {
    "id": "3f2b8c1e-5a47-4d1b-9c3e-1a2b3c4d5e6f",
    "accountNumber": "FC1000000001",
    "type": "SAVINGS",
    "currency": "INR",
    "balance": 15000.00,
    "status": "ACTIVE",
    "dailyTransferLimit": 100000.00,
    "createdAt": "2026-11-23T15:04:05.123456Z"
  },
  {
    "id": "5e4d3c2b-1a09-4f8e-b7d6-c5b4a3928170",
    "accountNumber": "FC1000000003",
    "type": "CURRENT",
    "currency": "INR",
    "balance": 0.00,
    "status": "ACTIVE",
    "dailyTransferLimit": 100000.00,
    "createdAt": "2026-11-23T15:10:41.552300Z"
  }
]
```

---

## 3. Get one account

| | |
| --- | --- |
| Method / URL | `GET http://localhost:8080/api/v1/accounts/{accountId}` |
| Direct URL | `GET http://localhost:8083/api/v1/accounts/{accountId}` |

**Headers**: `Authorization: Bearer <token>` (required).

**curl**

```bash
curl -i "$BASE/api/v1/accounts/$ACCOUNT_ID" -H "Authorization: Bearer $TOKEN"
```

**Success: `200 OK`**

```
HTTP/1.1 200
Content-Type: application/json
X-Request-Id: 3d4e5f6a-7b8c-4d9e-a0b1-c2d3e4f5a6b7
```

```json
{
  "id": "3f2b8c1e-5a47-4d1b-9c3e-1a2b3c4d5e6f",
  "accountNumber": "FC1000000001",
  "type": "SAVINGS",
  "currency": "INR",
  "balance": 15000.00,
  "status": "ACTIVE",
  "dailyTransferLimit": 100000.00,
  "createdAt": "2026-11-23T15:04:05.123456Z"
}
```

**Errors**: `404 NOT_FOUND` (see above); `400` if `{accountId}` is not a UUID.

---

## 4. Deposit cash (simulated)

| | |
| --- | --- |
| Method / URL | `POST http://localhost:8080/api/v1/accounts/{accountId}/deposits` |
| Direct URL | `POST http://localhost:8083/api/v1/accounts/{accountId}/deposits` |

**Headers**: `Authorization: Bearer <token>` (required), `Content-Type: application/json` (required).

**Request body**

| Field | Type | Rules |
| --- | --- | --- |
| `amount` | number | required, minimum `0.01`, at most 15 integer digits and 2 decimals |

**curl**

```bash
curl -i -X POST "$BASE/api/v1/accounts/$ACCOUNT_ID/deposits" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"amount": 20000.00}'
```

**Success: `200 OK`**

```
HTTP/1.1 200
Content-Type: application/json
X-Request-Id: 8e9f0a1b-2c3d-4e5f-a6b7-c8d9e0f1a2b3
```

```json
{
  "journalId": "b1c2d3e4-f5a6-4b7c-8d9e-0f1a2b3c4d5e",
  "accountId": "3f2b8c1e-5a47-4d1b-9c3e-1a2b3c4d5e6f",
  "balance": 20000.00
}
```

| Field | Description |
| --- | --- |
| `journalId` | the ledger journal that recorded this movement |
| `accountId` | the account |
| `balance` | balance after the deposit |

**Errors**

`400 Bad Request`: validation

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Request validation failed",
  "instance": "/api/v1/accounts/3f2b8c1e-5a47-4d1b-9c3e-1a2b3c4d5e6f/deposits",
  "code": "VALIDATION_FAILED",
  "errors": {
    "amount": "must be greater than or equal to 0.01"
  }
}
```

`422 Unprocessable Entity`: account frozen or closed

```json
{
  "type": "about:blank",
  "title": "Unprocessable Entity",
  "status": 422,
  "detail": "Account FC1000000001 is not active",
  "instance": "/api/v1/accounts/3f2b8c1e-5a47-4d1b-9c3e-1a2b3c4d5e6f/deposits",
  "code": "ACCOUNT_NOT_ACTIVE"
}
```

`404 NOT_FOUND`: not your account. Note: deposits have no idempotency key, so a retried request deposits twice.

---

## 5. Withdraw cash (simulated)

| | |
| --- | --- |
| Method / URL | `POST http://localhost:8080/api/v1/accounts/{accountId}/withdrawals` |
| Direct URL | `POST http://localhost:8083/api/v1/accounts/{accountId}/withdrawals` |

**Headers** and **request body**: same as deposits.

**curl**

```bash
curl -i -X POST "$BASE/api/v1/accounts/$ACCOUNT_ID/withdrawals" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"amount": 500.00}'
```

**Success: `200 OK`**

```
HTTP/1.1 200
Content-Type: application/json
X-Request-Id: 4f5a6b7c-8d9e-4f0a-b1c2-d3e4f5a6b7c8
```

```json
{
  "journalId": "c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f",
  "accountId": "3f2b8c1e-5a47-4d1b-9c3e-1a2b3c4d5e6f",
  "balance": 19500.00
}
```

**Errors**

`422 Unprocessable Entity`: balance too low

```json
{
  "type": "about:blank",
  "title": "Unprocessable Entity",
  "status": 422,
  "detail": "Insufficient funds in account FC1000000001",
  "instance": "/api/v1/accounts/3f2b8c1e-5a47-4d1b-9c3e-1a2b3c4d5e6f/withdrawals",
  "code": "INSUFFICIENT_FUNDS"
}
```

Also `422 ACCOUNT_NOT_ACTIVE`, `400 VALIDATION_FAILED`, `404 NOT_FOUND` as for deposits.

---

## 6. Account statement (paged JSON)

Ledger entries of the account, newest first.

| | |
| --- | --- |
| Method / URL | `GET http://localhost:8080/api/v1/accounts/{accountId}/statement?page=0&size=20` |
| Direct URL | `GET http://localhost:8083/api/v1/accounts/{accountId}/statement?page=0&size=20` |

**Headers**: `Authorization: Bearer <token>` (required).

**Query parameters**

| Name | Default | Rules |
| --- | --- | --- |
| `page` | `0` | zero-based; negative values are treated as 0 |
| `size` | `20` | clamped to 1-100 |

**curl**

```bash
curl -i "$BASE/api/v1/accounts/$ACCOUNT_ID/statement?page=0&size=2" \
  -H "Authorization: Bearer $TOKEN"
```

**Success: `200 OK`**

```
HTTP/1.1 200
Content-Type: application/json
X-Request-Id: 5a6b7c8d-9e0f-4a1b-82c3-d4e5f6a7b8c9
```

```json
{
  "content": [
    {
      "id": "e5f6a7b8-c9d0-4e1f-a2b3-c4d5e6f7a8b9",
      "journalId": "c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f",
      "direction": "DEBIT",
      "amount": 500.00,
      "balanceAfter": 19500.00,
      "createdAt": "2026-11-23T15:20:11.904512Z"
    },
    {
      "id": "d4e5f6a7-b8c9-4d0e-91f2-b3c4d5e6f7a8",
      "journalId": "b1c2d3e4-f5a6-4b7c-8d9e-0f1a2b3c4d5e",
      "direction": "CREDIT",
      "amount": 20000.00,
      "balanceAfter": 20000.00,
      "createdAt": "2026-11-23T15:15:02.331870Z"
    }
  ],
  "page": 0,
  "size": 2,
  "totalElements": 3,
  "totalPages": 2
}
```

| Entry field | Description |
| --- | --- |
| `direction` | `CREDIT` increases the balance, `DEBIT` decreases it |
| `balanceAfter` | the account balance right after this entry |
| `journalId` | groups the entries of one business event (a transfer has a DEBIT and a CREDIT entry in the same journal) |

**Errors**: `404 NOT_FOUND`.

---

## 7. Account statement (CSV download)

| | |
| --- | --- |
| Method / URL | `GET http://localhost:8080/api/v1/accounts/{accountId}/statement.csv?from=2026-11-01&to=2026-11-30` |
| Direct URL | `GET http://localhost:8083/api/v1/accounts/{accountId}/statement.csv?from=2026-11-01&to=2026-11-30` |

**Headers**: `Authorization: Bearer <token>` (required).

**Query parameters**

| Name | Format | Rules |
| --- | --- | --- |
| `from` | `YYYY-MM-DD` | required; start of day in `Asia/Kolkata` |
| `to` | `YYYY-MM-DD` | required; inclusive; not before `from`; range at most 366 days |

At most 10,000 rows are returned, oldest first.

**curl**

```bash
curl -i "$BASE/api/v1/accounts/$ACCOUNT_ID/statement.csv?from=2026-11-01&to=2026-11-30" \
  -H "Authorization: Bearer $TOKEN" \
  -o statement.csv
```

**Success: `200 OK`**

```
HTTP/1.1 200
Content-Type: text/csv
Content-Disposition: attachment; filename="statement-FC1000000001-2026-11-01-2026-11-30.csv"
X-Request-Id: 6b7c8d9e-0f1a-4b2c-93d4-e5f6a7b8c9d0
```

```csv
date,journal_id,direction,amount,currency,balance_after
2026-11-23T20:45:02.331870,b1c2d3e4-f5a6-4b7c-8d9e-0f1a2b3c4d5e,CREDIT,20000.0000,INR,20000.0000
2026-11-23T20:50:11.904512,c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f,DEBIT,500.0000,INR,19500.0000
```

(`date` is local time in `Asia/Kolkata`; amounts keep the database scale of 4 decimals.)

**Errors**

`400 Bad Request`: invalid range

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "'to' must not precede 'from' and the range is at most one year",
  "instance": "/api/v1/accounts/3f2b8c1e-5a47-4d1b-9c3e-1a2b3c4d5e6f/statement.csv",
  "code": "INVALID_RANGE"
}
```

`400 Bad Request` (no `code`) when `from` or `to` is missing or not a date; `404 NOT_FOUND` for someone else's account.

---

## 8. Transfer money (account to account)

Moves money atomically between two accounts: balanced DEBIT and CREDIT ledger entries, the transfer record and a
`TransferCompleted` event commit together. `Idempotency-Key` is **required**.

| | |
| --- | --- |
| Method / URL | `POST http://localhost:8080/api/v1/transfers` |
| Direct URL | `POST http://localhost:8083/api/v1/transfers` |

**Headers**

| Header | Value | Required |
| --- | --- | --- |
| `Authorization` | `Bearer <token>` | yes |
| `Content-Type` | `application/json` | yes |
| `Idempotency-Key` | any unique string, 1-100 chars (a UUID is typical) | yes |

**Request body**

| Field | Type | Rules |
| --- | --- | --- |
| `sourceAccountId` | uuid | required, must be one of your accounts |
| `targetAccountNumber` | string | required, e.g. `FC1000000002` (any customer's account, not a system account) |
| `amount` | number | required, minimum `0.01`, at most 2 decimals |
| `currency` | string | optional, 3 letters, defaults to `INR` (only `INR` is supported) |
| `remarks` | string | optional, max 140 chars |

**curl**

```bash
curl -i -X POST "$BASE/api/v1/transfers" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 6f1d2c3b-4a59-4e87-9a01-b2c3d4e5f6a7" \
  -d '{
    "sourceAccountId": "3f2b8c1e-5a47-4d1b-9c3e-1a2b3c4d5e6f",
    "targetAccountNumber": "FC1000000002",
    "amount": 5000.00,
    "currency": "INR",
    "remarks": "Rent"
  }'
```

**Success: `201 Created`** (first time this key is used)

```
HTTP/1.1 201
Content-Type: application/json
X-Request-Id: 7c8d9e0f-1a2b-4c3d-a4e5-f6a7b8c9d0e1
```

```json
{
  "id": "a7b8c9d0-e1f2-4a3b-b4c5-d6e7f8a9b0c1",
  "sourceAccountId": "3f2b8c1e-5a47-4d1b-9c3e-1a2b3c4d5e6f",
  "targetAccountId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "amount": 5000.00,
  "currency": "INR",
  "status": "COMPLETED",
  "remarks": "Rent",
  "journalId": "f6a7b8c9-d0e1-4f2a-83b4-c5d6e7f8a9b0",
  "createdAt": "2026-11-23T15:30:44.120031Z"
}
```

**Success: `200 OK`** when the same `Idempotency-Key` and the same body are sent again: the stored result is returned and
nothing is debited again.

```
HTTP/1.1 200
Content-Type: application/json
Idempotent-Replayed: true
X-Request-Id: 8d9e0f1a-2b3c-4d4e-b5f6-a7b8c9d0e1f2
```

The body is identical to the `201` response.

**Errors**

`400 Bad Request`: header missing

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Idempotency-Key header (1-100 chars) is required",
  "instance": "/api/v1/transfers",
  "code": "IDEMPOTENCY_KEY_REQUIRED"
}
```

`400 Bad Request`: validation (`code: VALIDATION_FAILED`), e.g.

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Request validation failed",
  "instance": "/api/v1/transfers",
  "code": "VALIDATION_FAILED",
  "errors": {
    "amount": "numeric value out of bounds (<15 digits>.<2 digits> expected)",
    "targetAccountNumber": "must not be blank"
  }
}
```

`400 SAME_ACCOUNT` (`"detail": "Source and target account must differ"`) and `400 INVALID_TARGET`
(`"detail": "Cannot transfer to a system account"`).

`404 Not Found`: source account is not yours (`"detail": "Account not found"`) or the target number does not exist
(`"detail": "Target account not found"`), both `"code": "NOT_FOUND"`.

`422 Unprocessable Entity`: the possible codes and their `detail`:

| `code` | `detail` |
| --- | --- |
| `INSUFFICIENT_FUNDS` | `Insufficient funds in account FC1000000001` |
| `DAILY_LIMIT_EXCEEDED` | `Daily transfer limit exceeded` |
| `CURRENCY_MISMATCH` | `Cross-currency transfers are not supported` |
| `ACCOUNT_NOT_ACTIVE` | `Account FC1000000002 is not active` (either side frozen) |
| `FRAUD_REJECTED` | `Transfer rejected: Amount exceeds the single-transfer threshold of 200000.00` or `Transfer rejected: Too many transfers in the last minute` |
| `IDEMPOTENCY_KEY_REUSED` | `Idempotency-Key was already used with a different request` |

```json
{
  "type": "about:blank",
  "title": "Unprocessable Entity",
  "status": 422,
  "detail": "Daily transfer limit exceeded",
  "instance": "/api/v1/transfers",
  "code": "DAILY_LIMIT_EXCEEDED"
}
```

`429 Too Many Requests` from the gateway when rate limiting is enabled (see [README.md](README.md)).

A failed transfer leaves nothing behind (it rolls back completely), so the same key can be retried after fixing the
cause, for example after a deposit.

---

## 9. Get a transfer

| | |
| --- | --- |
| Method / URL | `GET http://localhost:8080/api/v1/transfers/{transferId}` |
| Direct URL | `GET http://localhost:8083/api/v1/transfers/{transferId}` |

Only the user who initiated the transfer can read it.

**Headers**: `Authorization: Bearer <token>` (required).

**curl**

```bash
curl -i "$BASE/api/v1/transfers/a7b8c9d0-e1f2-4a3b-b4c5-d6e7f8a9b0c1" \
  -H "Authorization: Bearer $TOKEN"
```

**Success: `200 OK`**

```
HTTP/1.1 200
Content-Type: application/json
X-Request-Id: 9e0f1a2b-3c4d-4e5f-86a7-b8c9d0e1f2a3
```

```json
{
  "id": "a7b8c9d0-e1f2-4a3b-b4c5-d6e7f8a9b0c1",
  "sourceAccountId": "3f2b8c1e-5a47-4d1b-9c3e-1a2b3c4d5e6f",
  "targetAccountId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "amount": 5000.00,
  "currency": "INR",
  "status": "COMPLETED",
  "remarks": "Rent",
  "journalId": "f6a7b8c9-d0e1-4f2a-83b4-c5d6e7f8a9b0",
  "createdAt": "2026-11-23T15:30:44.120031Z"
}
```

**Errors**: `404 NOT_FOUND` with `"detail": "Transfer not found"`.

---

## 10. Reconciliation report (admin)

Recomputes every account balance from the immutable ledger and checks that every journal balances and that all account
balances sum to zero. `consistent: false` means data corruption or a bug.

| | |
| --- | --- |
| Method / URL | `GET http://localhost:8080/api/v1/admin/reconciliation` |
| Direct URL | `GET http://localhost:8083/api/v1/admin/reconciliation` |

**Headers**: `Authorization: Bearer <admin token>` (required).

**curl**

```bash
curl -i "$BASE/api/v1/admin/reconciliation" -H "Authorization: Bearer $ADMIN_TOKEN"
```

**Success: `200 OK`** (healthy books)

```
HTTP/1.1 200
Content-Type: application/json
X-Request-Id: 0f1a2b3c-4d5e-4f6a-97b8-c9d0e1f2a3b4
```

```json
{
  "consistent": true,
  "sumOfAllBalances": 0.0000,
  "balanceMismatches": [],
  "unbalancedJournals": []
}
```

Example of a problem report:

```json
{
  "consistent": false,
  "sumOfAllBalances": 5.0000,
  "balanceMismatches": [
    {
      "accountId": "3f2b8c1e-5a47-4d1b-9c3e-1a2b3c4d5e6f",
      "accountNumber": "FC1000000001",
      "storedBalance": 105.0000,
      "ledgerBalance": 100.0000
    }
  ],
  "unbalancedJournals": []
}
```

**Errors**: `403 FORBIDDEN` for a customer token.

---

## 11. Freeze an account (admin)

A frozen account cannot send, receive, deposit or withdraw. The change is written to the audit log with the admin's
username.

| | |
| --- | --- |
| Method / URL | `POST http://localhost:8080/api/v1/admin/accounts/{accountId}/freeze` |
| Direct URL | `POST http://localhost:8083/api/v1/admin/accounts/{accountId}/freeze` |

**Headers**: `Authorization: Bearer <admin token>` (required). No request body.

**curl**

```bash
curl -i -X POST "$BASE/api/v1/admin/accounts/$ACCOUNT_ID/freeze" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

**Success: `200 OK`**

```
HTTP/1.1 200
Content-Type: application/json
X-Request-Id: 1a2b3c4d-5e6f-4a7b-a8c9-d0e1f2a3b4c5
```

```json
{
  "id": "3f2b8c1e-5a47-4d1b-9c3e-1a2b3c4d5e6f",
  "accountNumber": "FC1000000001",
  "type": "SAVINGS",
  "currency": "INR",
  "balance": 14500.00,
  "status": "FROZEN",
  "dailyTransferLimit": 100000.00,
  "createdAt": "2026-11-23T15:04:05.123456Z"
}
```

**Errors**

| HTTP | `code` | `detail` |
| --- | --- | --- |
| 404 | `NOT_FOUND` | `Account not found` |
| 400 | `INVALID_ACCOUNT_TYPE` | `The system account cannot be frozen` |
| 409 | `ACCOUNT_CLOSED` | `A closed account cannot be changed` |
| 403 | `FORBIDDEN` | customer token used |

## 12. Unfreeze an account (admin)

| | |
| --- | --- |
| Method / URL | `POST http://localhost:8080/api/v1/admin/accounts/{accountId}/unfreeze` |
| Direct URL | `POST http://localhost:8083/api/v1/admin/accounts/{accountId}/unfreeze` |

**Headers** and errors: same as freeze.

**curl**

```bash
curl -i -X POST "$BASE/api/v1/admin/accounts/$ACCOUNT_ID/unfreeze" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

**Success: `200 OK`**: the account object as above with `"status": "ACTIVE"`.

---

## Other endpoints on this service

Reachable only through the direct port `8083` (not routed by the gateway); see
[admin-operations.md](admin-operations.md): `GET /api/v1/admin/audit`, `GET /api/v1/admin/outbox/summary`,
`GET /api/v1/admin/outbox/failed`, `POST /api/v1/admin/outbox/{id}/replay`.
Service-to-service endpoints are in [internal-apis.md](internal-apis.md).
