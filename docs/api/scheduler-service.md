# scheduler-service API

Standing instructions: recurring transfers (daily, weekly or monthly). A job runs every day at 06:00 (`Asia/Kolkata`)
and executes everything due by calling banking-core's transfer path with the idempotency key
`SI-<instructionId>-<runDate>`, so a retry or a second scheduler instance can never pay twice.

- Gateway: `http://localhost:8080/api/v1/standing-instructions...` and `/api/v1/admin/standing-instructions/run`
- Direct: `http://localhost:8086/...`
- Authentication: `Authorization: Bearer <access token>`. Sections 1-5 need role `CUSTOMER`; section 6 needs `ADMIN`.

```bash
export BASE=http://localhost:8080
export TOKEN="<customer accessToken>"
export ADMIN_TOKEN="<admin accessToken>"
export SI_ID="1b2c3d4e-5f60-4a7b-8c9d-0e1f2a3b4c5d"
```

Instruction response:

| Field | Type | Description |
| --- | --- | --- |
| `id` | uuid | instruction id |
| `sourceAccountId` | uuid | account the money leaves |
| `targetAccountNumber` | string | account number the money goes to |
| `amount` | number | 2 decimals |
| `frequency` | string | `DAILY`, `WEEKLY` or `MONTHLY` |
| `nextRunDate` | string | `YYYY-MM-DD`, the next date it is due |
| `status` | string | `ACTIVE`, `PAUSED` or `CANCELLED` |
| `lastRunStatus` | string | outcome of the last run, absent before the first run |

`lastRunStatus` values: `OK`, `OK (already executed)` (an idempotent replay), `FAILED: <code>` where `<code>` is the
banking-core error code (`INSUFFICIENT_FUNDS`, `ACCOUNT_NOT_ACTIVE`, `DAILY_LIMIT_EXCEEDED`, `FRAUD_REJECTED`, ...) or
`FAILED: ERROR`. A failed run is recorded and the schedule moves on to the next date. If banking-core is unreachable the
run is **deferred**: nothing is recorded and the schedule does not advance, so the next job run retries it.

Month arithmetic clamps to month end (31 Jan + 1 month = 28 Feb).

Errors common to all endpoints: `401` (empty body) without a valid token; `403 FORBIDDEN` for the wrong role.

---

## 1. Create a standing instruction

| | |
| --- | --- |
| Method / URL | `POST http://localhost:8080/api/v1/standing-instructions` |
| Direct URL | `POST http://localhost:8086/api/v1/standing-instructions` |

**Headers**: `Authorization: Bearer <token>` (required), `Content-Type: application/json` (required).

**Request body**

| Field | Type | Rules |
| --- | --- | --- |
| `sourceAccountId` | uuid | required, must be your account |
| `targetAccountNumber` | string | required, must exist, must differ from the source |
| `amount` | number | required, minimum `0.01`, at most 2 decimals |
| `frequency` | string | required, `DAILY`, `WEEKLY` or `MONTHLY` |
| `firstRunDate` | string | required, `YYYY-MM-DD`, today (`Asia/Kolkata`) or later |
| `remarks` | string | optional, max 140 chars |

**curl**

```bash
curl -i -X POST "$BASE/api/v1/standing-instructions" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "sourceAccountId": "3f2b8c1e-5a47-4d1b-9c3e-1a2b3c4d5e6f",
    "targetAccountNumber": "FC1000000002",
    "amount": 5000.00,
    "frequency": "MONTHLY",
    "firstRunDate": "2026-12-01",
    "remarks": "Rent"
  }'
```

**Success: `201 Created`**

```
HTTP/1.1 201
Content-Type: application/json
X-Request-Id: 6f7a8b9c-0d1e-4f2a-b14c-d5e6f7a8b9c0
```

```json
{
  "id": "1b2c3d4e-5f60-4a7b-8c9d-0e1f2a3b4c5d",
  "sourceAccountId": "3f2b8c1e-5a47-4d1b-9c3e-1a2b3c4d5e6f",
  "targetAccountNumber": "FC1000000002",
  "amount": 5000.00,
  "frequency": "MONTHLY",
  "nextRunDate": "2026-12-01",
  "status": "ACTIVE"
}
```

**Errors**

`400 Bad Request`: first run in the past

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "The first run date cannot be in the past",
  "instance": "/api/v1/standing-instructions",
  "code": "INVALID_START_DATE"
}
```

`400 SAME_ACCOUNT` (`"detail": "Source and target account must differ"`).

`400 VALIDATION_FAILED`

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Request validation failed",
  "instance": "/api/v1/standing-instructions",
  "code": "VALIDATION_FAILED",
  "errors": {
    "amount": "must be greater than or equal to 0.01",
    "frequency": "must not be null"
  }
}
```

`404 NOT_FOUND`: the source account is not yours, or the target number does not exist (`"detail": "Account not found"`).

`503 DEPENDENCY_UNAVAILABLE`: banking-core could not be reached to validate the accounts.

---

## 2. List my standing instructions

| | |
| --- | --- |
| Method / URL | `GET http://localhost:8080/api/v1/standing-instructions` |
| Direct URL | `GET http://localhost:8086/api/v1/standing-instructions` |

**Headers**: `Authorization: Bearer <token>` (required).

**curl**

```bash
curl -i "$BASE/api/v1/standing-instructions" -H "Authorization: Bearer $TOKEN"
```

**Success: `200 OK`** (newest first; `[]` when none)

```
HTTP/1.1 200
Content-Type: application/json
X-Request-Id: 7a8b9c0d-1e2f-4a3b-a25d-e6f7a8b9c0d1
```

```json
[
  {
    "id": "1b2c3d4e-5f60-4a7b-8c9d-0e1f2a3b4c5d",
    "sourceAccountId": "3f2b8c1e-5a47-4d1b-9c3e-1a2b3c4d5e6f",
    "targetAccountNumber": "FC1000000002",
    "amount": 5000.00,
    "frequency": "MONTHLY",
    "nextRunDate": "2027-01-01",
    "status": "ACTIVE",
    "lastRunStatus": "OK"
  }
]
```

---

## 3. Pause

A paused instruction is skipped by the job.

| | |
| --- | --- |
| Method / URL | `POST http://localhost:8080/api/v1/standing-instructions/{id}/pause` |
| Direct URL | `POST http://localhost:8086/api/v1/standing-instructions/{id}/pause` |

**Headers**: `Authorization: Bearer <token>` (required). No body.

**curl**

```bash
curl -i -X POST "$BASE/api/v1/standing-instructions/$SI_ID/pause" -H "Authorization: Bearer $TOKEN"
```

**Success: `200 OK`**

```
HTTP/1.1 200
Content-Type: application/json
X-Request-Id: 8b9c0d1e-2f3a-4b4c-b36e-f7a8b9c0d1e2
```

```json
{
  "id": "1b2c3d4e-5f60-4a7b-8c9d-0e1f2a3b4c5d",
  "sourceAccountId": "3f2b8c1e-5a47-4d1b-9c3e-1a2b3c4d5e6f",
  "targetAccountNumber": "FC1000000002",
  "amount": 5000.00,
  "frequency": "MONTHLY",
  "nextRunDate": "2027-01-01",
  "status": "PAUSED",
  "lastRunStatus": "OK"
}
```

**Errors**: `404 NOT_FOUND` (`"detail": "Standing instruction not found"`, also for another user's instruction);
`409 INSTRUCTION_CANCELLED` (`"detail": "A cancelled instruction cannot be changed"`).

## 4. Resume

Reactivates a paused instruction. Runs missed while it was paused are **not** replayed: `nextRunDate` is moved to today if
it is in the past.

| | |
| --- | --- |
| Method / URL | `POST http://localhost:8080/api/v1/standing-instructions/{id}/resume` |
| Direct URL | `POST http://localhost:8086/api/v1/standing-instructions/{id}/resume` |

**Headers**, **errors**: as for pause.

**curl**

```bash
curl -i -X POST "$BASE/api/v1/standing-instructions/$SI_ID/resume" -H "Authorization: Bearer $TOKEN"
```

**Success: `200 OK`**: the instruction with `"status": "ACTIVE"`.

## 5. Cancel

Permanent: a cancelled instruction cannot be paused or resumed.

| | |
| --- | --- |
| Method / URL | `POST http://localhost:8080/api/v1/standing-instructions/{id}/cancel` |
| Direct URL | `POST http://localhost:8086/api/v1/standing-instructions/{id}/cancel` |

**Headers**: as for pause. **Errors**: `404 NOT_FOUND`.

**curl**

```bash
curl -i -X POST "$BASE/api/v1/standing-instructions/$SI_ID/cancel" -H "Authorization: Bearer $TOKEN"
```

**Success: `200 OK`**: the instruction with `"status": "CANCELLED"`.

---

## 6. Run due instructions now (admin)

Operator trigger for the daily job: useful for demos and for catching up after downtime. Executes everything due on or
before today, one run per missed day, each with its own idempotency key.

| | |
| --- | --- |
| Method / URL | `POST http://localhost:8080/api/v1/admin/standing-instructions/run` |
| Direct URL | `POST http://localhost:8086/api/v1/admin/standing-instructions/run` |

**Headers**: `Authorization: Bearer <admin token>` (required). No body.

**curl**

```bash
curl -i -X POST "$BASE/api/v1/admin/standing-instructions/run" -H "Authorization: Bearer $ADMIN_TOKEN"
```

**Success: `200 OK`**

```
HTTP/1.1 200
Content-Type: application/json
X-Request-Id: 9c0d1e2f-3a4b-4c5d-847f-a8b9c0d1e2f3
```

```json
{
  "succeeded": 3,
  "failed": 1,
  "deferred": 0
}
```

| Field | Meaning |
| --- | --- |
| `succeeded` | runs that transferred the money (including idempotent replays) |
| `failed` | runs rejected for a business reason and recorded on the instruction |
| `deferred` | instructions skipped because banking-core was unreachable; they will be retried |

**Errors**: `403 FORBIDDEN` for a customer token.

---

## Other endpoints on this service

Not exposed: this service has no admin outbox/audit endpoints (it does not use the outbox). Actuator endpoints are
listed in [admin-operations.md](admin-operations.md).
