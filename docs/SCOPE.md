# FinCore scope and status

Priorities come from the project brief (P0 to P5). The recommended final scope is P0 to P3 done thoroughly
(real tests, failure handling, concurrency protection, monitoring), then selected P4/P5 modules.

Status legend: done = written, **unverified** (nothing has been compiled or run yet), partial, todo.

## Architecture status

The platform was first written as a modular monolith and then restructured into a multi-module Maven build of
7 deployable services + 2 shared libraries (see `README.md`). Database per service, Kafka + outbox for events,
`/internal` REST with idempotent endpoints for synchronous calls.

## P0: banking foundation

| Module | Service | Status | Notes |
| --- | --- | --- | --- |
| Authentication | auth-service | done | JWT, rotating refresh tokens with reuse detection, logout, login audit |
| Customer | customer-service | partial | profile + simulated KYC, created from `UserRegistered`; address and documents todo |
| Account | banking-core | partial | savings/current, daily limit, admin freeze/unfreeze (audited); joint accounts todo |
| Double-entry ledger | banking-core | done | append-only, balanced per currency, DB trigger, idempotent external postings |
| Fund transfer | banking-core | partial | internal + idempotency + outbox; recurring via scheduler-service |

## P1: advanced backend

| Module | Status | Notes |
| --- | --- | --- |
| Kafka | partial | outbox publisher, idempotent consumers, DLT, admin replay; retry topics, schema registry, Kafka integration test todo |
| Notification | partial | transfer + payment notifications, idempotent; email/push todo |
| Fraud | partial | high-value + velocity rules (strategy); risk scoring todo |
| Redis | partial | sliding-window rate limiting at the gateway (opt-in); cache-aside and distributed lock todo |
| API gateway | done | routing, internal path block, request ids, rate limiting; JWT validation at the edge todo |
| Resilience4j | partial | retry + circuit breaker on the three `/internal` clients; bulkhead / time limiter todo |

## P2: distributed workflows

| Module | Status | Notes |
| --- | --- | --- |
| Payments (UPI/IMPS/NEFT) | partial | Saga with compensation and recovery across payment-service and banking-core; webhooks todo |
| Reconciliation | partial | ledger vs balances; settlement matching against the bank gateway todo |
| Scheduler | partial | standing instructions with idempotent runs; batch settlement todo |
| Audit | partial | append-only audit log per service + admin API; change tracking via aspect, central aggregation todo |

## P3: production engineering

| Item | Status |
| --- | --- |
| Dockerfile (one, parameterised by module), Compose (Postgres, Kafka, Redis, 7 services) | done |
| Kubernetes manifests per service, HPAs, config | done |
| GitHub Actions CI (format check, full build + tests, 7 image builds) | done |
| Prometheus endpoint on every service | done (Grafana dashboards todo) |
| OpenTelemetry tracing, k6 load tests, Helm, Terraform | todo |

## P4/P5

Loans, cards, deposits, wallet, reporting, AI assistant: not started.

## Suggested next steps

1. Compile, format (`mvn spotless:apply`), run `mvn verify`, fix what breaks. Nothing has been executed yet,
   including roughly 250 tests across the modules.
2. One end-to-end test over the real services (Testcontainers Kafka + Postgres, compose) for register to transfer
   and payment refund.
3. Kafka integration test: outbox to topic, duplicate delivery, DLT.
4. Distributed tracing (OpenTelemetry) so one request can be followed across services, then Grafana dashboards and
   a k6 transfer load test.
