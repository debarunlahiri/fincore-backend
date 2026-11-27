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
| Security | auth-service | done | lockout after 5 failures, login history, password change, one active device per user |
| Payees + OTP | banking-core | done | OTP-confirmed payees with cooling period; OTP bound to the exact transfer above a threshold |
| Account | banking-core | partial | savings/current, daily limit, admin freeze/unfreeze (audited); joint accounts todo |
| Double-entry ledger | banking-core | done | append-only, balanced per currency, DB trigger, idempotent external postings |
| Fund transfer | banking-core | partial | internal + idempotency + outbox; saved payee, OTP, per-transaction limit, receipts by reference, filtered history; recurring via scheduler-service; fees, cut-off times todo |
| Public identifiers | all | done | customers see references and account numbers only; internal UUIDs are never in the API |

## P1: advanced backend

| Module | Status | Notes |
| --- | --- | --- |
| Kafka | partial | outbox publisher, idempotent consumers, DLT, admin replay; retry topics, schema registry todo; Kafka integration test written (`KafkaFlowIntegrationTest`: outbox to topic, duplicate delivery, DLT), unverified |
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
| OpenTelemetry tracing | written, unverified (Micrometer bridge + OTLP exporter in every service, Jaeger in Compose; set `OTLP_ENDPOINT`, `TRACING_SAMPLING`) |
| k6 load tests, Helm, Terraform | todo |

## P4/P5

Fixed deposits, loans (apply, OTP acceptance, EMI schedule, repayment, foreclosure, overdue fees) and debit cards (issue, block, limits, PIN, purchase simulator) are written (unverified). Still todo: credit cards, wallet, reporting, AI assistant, loan collections, GST/fee schedules, joint accounts.

## Suggested next steps

0. Everything written since the first build is unverified: `banking-core` migration `V2__banking_features.sql`, the auth `V2__security.sql`, payment and scheduler `V2__public_reference.sql`, and the reworked tests. Compile and run `mvn verify` first; expect fixes.

1. Compile, format (`mvn spotless:apply`), run `mvn verify`, fix what breaks. Nothing has been executed yet,
   including roughly 250 tests across the modules.
2. One end-to-end test over the real services (Testcontainers Kafka + Postgres, compose) for register to transfer
   and payment refund.
3. Run `KafkaFlowIntegrationTest` (needs Docker) and fix what breaks.
4. Verify tracing in Jaeger across services, then Grafana dashboards and a k6 transfer load test.
