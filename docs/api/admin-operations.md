# Admin and operations endpoints

Endpoints that exist on several services because they come from the shared `common-data` library (outbox dead-letter
tools and the audit log), plus the actuator endpoints every service exposes.

**These are not routed by the gateway.** Call them on the service's own port. All admin endpoints need
`Authorization: Bearer <staff access token>`; a customer token, or a staff role without access, gets `403 FORBIDDEN`.
Who may call what: outbox `summary` and `failed` are open to `ADMIN`, `OPERATIONS` and `COMPLIANCE`; outbox `replay`
needs `ADMIN` or `OPERATIONS`; the audit log needs `ADMIN` or `COMPLIANCE`. Full matrix in
[README.md](README.md#authentication).

| Service | Port | Outbox + audit endpoints | What its outbox/audit contains |
| --- | --- | --- | --- |
| auth-service | 8081 | yes | `UserRegistered` events; login audit (`LOGIN_SUCCEEDED`, `LOGIN_FAILED`) |
| customer-service | 8082 | yes | (outbox unused); audit |
| banking-core | 8083 | yes | `TransferCompleted`, `MoneyDeposited`, `MoneyWithdrawn` events; `ACCOUNT_FROZEN` / `ACCOUNT_UNFROZEN` audit |
| payment-service | 8084 | yes | `PaymentInitiated` / `Settled` / `Refunded` / `Failed` events; `PAYMENT_REFUNDED` audit |
| notification-service | 8085 | yes | (outbox unused); audit |
| scheduler-service | 8086 | no | |
| api-gateway | 9780 | no | |

```bash
export ADMIN_TOKEN="<admin accessToken>"
export SVC=http://localhost:8083      # pick the service port
```

The outbox is the transactional queue between a service's database and Kafka. An event is `PENDING` until it is
published, `PUBLISHED` afterwards, and `FAILED` after 5 unsuccessful publish attempts (the dead-letter state).

---

## 1. Outbox summary

| | |
| --- | --- |
| Method / URL | `GET http://localhost:8083/api/v1/admin/outbox/summary` (use the service's port) |

**Headers**: `Authorization: Bearer <admin token>` (required).

**curl**

```bash
curl -i "$SVC/api/v1/admin/outbox/summary" -H "Authorization: Bearer $ADMIN_TOKEN"
```

**Success: `200 OK`**

```
HTTP/1.1 200
Content-Type: application/json
```

```json
{
  "PENDING": 0,
  "PUBLISHED": 27,
  "FAILED": 1
}
```

**Errors**: `401` (empty body) without a token, `403 FORBIDDEN` for a customer token.

---

## 2. List failed (dead-lettered) outbox events

Returns up to 100 events, oldest first.

| | |
| --- | --- |
| Method / URL | `GET http://localhost:8083/api/v1/admin/outbox/failed` |

**Headers**: `Authorization: Bearer <admin token>` (required).

**curl**

```bash
curl -i "$SVC/api/v1/admin/outbox/failed" -H "Authorization: Bearer $ADMIN_TOKEN"
```

**Success: `200 OK`**

```
HTTP/1.1 200
Content-Type: application/json
```

```json
[
  {
    "id": "2c3d4e5f-6a7b-4c8d-9e0f-1a2b3c4d5e6f",
    "eventType": "TransferCompleted",
    "aggregateId": "a7b8c9d0-e1f2-4a3b-b4c5-d6e7f8a9b0c1",
    "attempts": 5,
    "lastError": "Kafka did not acknowledge event 2c3d4e5f-6a7b-4c8d-9e0f-1a2b3c4d5e6f",
    "createdAt": "2026-11-23T15:30:44.150321Z"
  }
]
```

`[]` when there are none.

---

## 3. Replay a failed outbox event

Puts a `FAILED` event back in the queue with a fresh retry budget. Safe to repeat: consumers deduplicate on the event id.

| | |
| --- | --- |
| Method / URL | `POST http://localhost:8083/api/v1/admin/outbox/{eventId}/replay` |

**Headers**: `Authorization: Bearer <admin token>` (required). No body.

**curl**

```bash
curl -i -X POST "$SVC/api/v1/admin/outbox/2c3d4e5f-6a7b-4c8d-9e0f-1a2b3c4d5e6f/replay" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

**Success: `200 OK`**

```
HTTP/1.1 200
Content-Type: application/json
```

```json
{
  "id": "2c3d4e5f-6a7b-4c8d-9e0f-1a2b3c4d5e6f",
  "eventType": "TransferCompleted",
  "aggregateId": "a7b8c9d0-e1f2-4a3b-b4c5-d6e7f8a9b0c1",
  "attempts": 0,
  "createdAt": "2026-11-23T15:30:44.150321Z"
}
```

**Errors**

`404 Not Found`

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Outbox event not found",
  "instance": "/api/v1/admin/outbox/2c3d4e5f-6a7b-4c8d-9e0f-1a2b3c4d5e6f/replay",
  "code": "NOT_FOUND"
}
```

`409 Conflict`: the event is not `FAILED`

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Only FAILED events can be replayed",
  "instance": "/api/v1/admin/outbox/2c3d4e5f-6a7b-4c8d-9e0f-1a2b3c4d5e6f/replay",
  "code": "NOT_FAILED"
}
```

---

## 4. Audit log

Append-only record of security- and money-relevant actions of that service (the database rejects updates and deletes).

| | |
| --- | --- |
| Method / URL | `GET http://localhost:8081/api/v1/admin/audit?actor=alice&page=0&size=50` (use the service's port) |

**Headers**: `Authorization: Bearer <admin token>` (required).

**Query parameters**

| Name | Default | Rules |
| --- | --- | --- |
| `actor` | none (all actors) | exact match on the actor (a username, `system`, or an admin username) |
| `page` | `0` | zero-based |
| `size` | `50` | clamped to 1-200 |

**curl**

```bash
curl -i "http://localhost:8081/api/v1/admin/audit?actor=alice&page=0&size=50" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

**Success: `200 OK`** (newest first)

```
HTTP/1.1 200
Content-Type: application/json
```

```json
{
  "content": [
    {
      "id": "3d4e5f6a-7b8c-4d9e-a0f1-2b3c4d5e6f70",
      "actor": "alice",
      "action": "LOGIN_SUCCEEDED",
      "entityType": "USER",
      "entityId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
      "createdAt": "2026-11-23T15:00:12.447802Z"
    },
    {
      "id": "4e5f6a7b-8c9d-4e0f-b1a2-3c4d5e6f7a81",
      "actor": "alice",
      "action": "LOGIN_FAILED",
      "entityType": "USER",
      "createdAt": "2026-11-23T14:59:40.118264Z"
    }
  ],
  "page": 0,
  "size": 50,
  "totalElements": 2,
  "totalPages": 1
}
```

| Field | Description |
| --- | --- |
| `actor` | who did it (the raw username for a failed login, `system` for automated actions) |
| `action` | `LOGIN_SUCCEEDED`, `LOGIN_FAILED`, `ACCOUNT_FROZEN`, `ACCOUNT_UNFROZEN`, `PAYMENT_REFUNDED` |
| `entityType`, `entityId` | the affected object (`USER`, `ACCOUNT`, `PAYMENT`) |
| `details` | free text, e.g. the refund reason |

---

## 5. Actuator (health, metrics)

Available on **every** service and the gateway, at the service's port. No authentication.

| Endpoint | Purpose |
| --- | --- |
| `GET /actuator/health` | overall health |
| `GET /actuator/health/liveness` | Kubernetes liveness probe |
| `GET /actuator/health/readiness` | Kubernetes readiness probe |
| `GET /actuator/info` | build info (usually `{}`) |
| `GET /actuator/prometheus` | Prometheus metrics in text format |

```bash
curl -i http://localhost:8083/actuator/health
```

```
HTTP/1.1 200
Content-Type: application/vnd.spring-boot.actuator.v3+json
```

```json
{"status":"UP"}
```

Through the gateway, `http://localhost:9781/actuator/health` reports the gateway itself.

Metrics example:

```bash
curl -s http://localhost:8083/actuator/prometheus | head -n 5
```

```
# HELP jvm_memory_used_bytes The amount of used memory
# TYPE jvm_memory_used_bytes gauge
jvm_memory_used_bytes{area="heap",id="G1 Eden Space",} 4.1943040E7
```


## Merchant back office (banking-core, via the gateway)

`/api/v1/admin/merchants`. Read endpoints: ADMIN, OPERATIONS and COMPLIANCE. Actions: ADMIN and OPERATIONS.

| Endpoint | Notes |
| --- | --- |
| `GET /summary` | merchant counts, today/month volume, fees earned this month, pending settlement, merchants on settlement hold, royalty on hold |
| `GET /?query=&status=` | latest 100 merchants, search by name or code, `status` is `ACTIVE` or `SUSPENDED`. No internal ids or owner ids. |
| `POST /{merchantCode}/suspend`, `/reactivate` | platform suspension, audited. Suspending a main merchant also stops its outlets taking payments. |
| `POST /{merchantCode}/settle` | settle one merchant now (ignores retry back-off) |
| `POST /settle-due` | run the daily settlement for every merchant that is due, returns `{settled}` |
