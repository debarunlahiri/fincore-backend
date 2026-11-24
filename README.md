# FinCore Banking Platform (microservices)

Java 21, Spring Boot 3.4, Spring Cloud Gateway, PostgreSQL (database per service), Kafka, Redis, Resilience4j,
Flyway, Testcontainers. All banking rails, KYC and money are simulated.

> Status: written but **not yet compiled or run** (see `docs/SCOPE.md`). Expect to fix first-build errors.

## Architecture

```
                         ┌──────────────┐
   client ──HTTPS──────► │ api-gateway  │  routing, request ids, /internal blocked, Redis rate limit
                         └──────┬───────┘
        ┌───────────────┬───────┴────────┬──────────────────┬───────────────────┐
   auth-service   customer-service   banking-core      payment-service    scheduler-service
   users, JWT,    profiles, KYC      accounts, LEDGER,  Saga: ledger +     standing
   refresh tokens                    transfers, fraud,  simulated bank     instructions
        │               ▲            reconciliation          │                   │
        │  UserRegistered│                 ▲   /internal      │  /internal        │ /internal
        └──── Kafka ─────┘                 └──────────────────┴───────────────────┘
                                   TransferCompleted, Payment* ──► notification-service (idempotent consumer)
```

| Module | Port | Owns (own database) | Notes |
| --- | --- | --- | --- |
| `api-gateway` | 8080 | none | Spring Cloud Gateway (reactive) |
| `auth-service` | 8081 | users, refresh tokens | issues JWTs; emits `UserRegistered` |
| `customer-service` | 8082 | customers, KYC | creates profiles from `UserRegistered` |
| `banking-core` | 8083 | accounts, ledger, transfers | the consistency boundary |
| `payment-service` | 8084 | payments | distributed Saga |
| `notification-service` | 8085 | notifications | Kafka consumer |
| `scheduler-service` | 8086 | standing instructions | daily job |
| `common` | | | error model, JWT verification, internal-auth, `InternalRestClient` |
| `common-data` | | | transactional outbox, Kafka publisher, idempotent-consumer ledger, audit |

### Why this split

- **Accounts, ledger, transfers and cash live in one service** (`banking-core`). A transfer must debit one balance
  and credit another atomically, and ledger entries must commit with them. Splitting those across services would
  turn the most important operation into a distributed transaction. One service, one database, one ACID
  transaction: this is the consistency boundary, and everything else talks to it.
- **Ownership checks never leave `banking-core`**: an account stores its owner's `user_id` (from the JWT), so
  transfers do not call the customer-service. The only synchronous cross-service call on a user path is the KYC
  check when opening an account.
- **Database per service, no cross-database foreign keys**; other services' ids are stored by value.

### How services talk

| Style | Used for | Failure handling |
| --- | --- | --- |
| Kafka + transactional outbox | `UserRegistered`, `TransferCompleted`, `Payment*` | at-least-once; consumers dedupe on the event id; retries then dead-letter topic; admin replay |
| Synchronous REST on `/internal/**` | KYC lookup, payment debit/reversal, scheduled transfers | `X-Internal-Key` auth; timeouts; Resilience4j retry + circuit breaker only on `DependencyUnavailableException`; every mutating endpoint is idempotent so retries are safe |

`/internal/**` is never routed by the gateway (and blocked there explicitly), and user JWTs do not carry the
`INTERNAL` role, so end users cannot reach it.

## Project structure

```
fincore-backend/
├── pom.xml                      parent (module list, dependency management)
├── common/                      shared library
├── common-data/                 shared library (outbox, audit, idempotent consumer)
├── api-gateway/
├── auth-service/  customer-service/  banking-core/  payment-service/  notification-service/  scheduler-service/
├── docker/  k8s/  docs/  .github/workflows/
└── Dockerfile  docker-compose.yml
```

Every service is layered the same way, one package per responsibility under its own base package:

```
customer-service/src/main/java/com/fincore/
├── CustomerServiceApplication.java      lives in the shared root package so scanning also finds common / common-data
└── customer/
    ├── controller/    REST endpoints (public API and /internal API)
    ├── service/       business logic, transactions
    ├── repository/    Spring Data interfaces
    ├── entity/        JPA entities and their enums
    ├── dto/           request / response records (never expose entities)
    ├── provider/      pluggable strategies (KYC)
    ├── listener/      Kafka consumers (thin adapters)
    └── config/        security, topics, bootstrap
```

| Package | Used by | Holds |
| --- | --- | --- |
| `controller`, `service`, `repository`, `entity`, `dto`, `config` | every service | the standard layers |
| `client` | banking-core, payment, scheduler | adapters to other services (`CustomerClient`, `LedgerClient`/`LedgerPort`, `CoreClient`/`CorePort`) |
| `event` | auth, payment, banking-core | outbox event payloads |
| `listener` | customer, notification | Kafka consumers |
| `job` | payment, scheduler | scheduled jobs (recovery, daily run) |
| `rule` | banking-core (`fraud`) | strategy implementations |
| `model` | banking-core (`ledger`) | domain value objects (`Posting`) |
| `bank` | payment | the simulated external bank |
| `filter`, `limiter` | api-gateway | gateway filters and the Redis rate limiter |

Dependencies point one way: `controller → service → repository → entity`, with `dto` used at the edges. Tests mirror
the main packages; `*IntegrationTest` classes live in an `integration` package and extend each module's
`AbstractIntegrationTest`.

## Run

```bash
docker compose up --build            # everything (postgres, kafka, redis + 7 services)
# or only the infrastructure, services from your IDE / `mvn -pl <module> spring-boot:run`:
docker compose up -d postgres kafka redis
```

Gateway: http://localhost:8080. Dev admin: `admin` / `ChangeMe!123` (override `ADMIN_PASSWORD`). Set `JWT_SECRET`
(Base64, 32+ bytes) and `INTERNAL_API_KEY` outside local development. Set `KAFKA_ENABLED=false` to run a service
without a broker (events are then only logged). Swagger UI is on each service (`/swagger-ui.html`).

## API documentation

Full reference, one file per service, with URLs, headers, request fields, curl commands, complete responses and
error bodies: **[docs/api/README.md](docs/api/README.md)**.

| Service | Document |
| --- | --- |
| auth-service | [docs/api/auth-service.md](docs/api/auth-service.md) |
| customer-service | [docs/api/customer-service.md](docs/api/customer-service.md) |
| banking-core | [docs/api/banking-core.md](docs/api/banking-core.md) |
| payment-service | [docs/api/payment-service.md](docs/api/payment-service.md) |
| scheduler-service | [docs/api/scheduler-service.md](docs/api/scheduler-service.md) |
| outbox / audit / actuator | [docs/api/admin-operations.md](docs/api/admin-operations.md) |
| service-to-service | [docs/api/internal-apis.md](docs/api/internal-apis.md) |

## API walk-through (via the gateway)

| Step | Call |
| --- | --- |
| Register | `POST /api/v1/auth/register` (profile appears asynchronously in customer-service) |
| Login / refresh / logout | `POST /api/v1/auth/login`, `/refresh`, `/logout` |
| Profile, KYC (simulated) | `GET/PUT /api/v1/customers/me`, `POST /api/v1/customers/me/kyc` `{"documentType":"PAN","documentNumber":"ABCDE1234F"}` |
| Open account | `POST /api/v1/accounts` `{"type":"SAVINGS"}` (needs verified KYC) |
| Deposit / withdraw | `POST /api/v1/accounts/{id}/deposits`, `/withdrawals` |
| Transfer | `POST /api/v1/transfers` with header `Idempotency-Key` |
| Payment (UPI/IMPS/NEFT) | `POST /api/v1/payments` with header `Idempotency-Key` |
| Standing instructions | `POST/GET /api/v1/standing-instructions`, `/{id}/pause\|resume\|cancel` |
| Statement | `GET /api/v1/accounts/{id}/statement`, `/statement.csv?from=&to=` |
| Staff roles | `ADMIN` (all, plus `POST/GET /api/v1/admin/staff`), `OPERATIONS` (freeze, replay, run instructions), `COMPLIANCE` (audit, reconciliation, read-only outbox); matrix in [docs/api/README.md](docs/api/README.md#authentication) |
| Admin | `GET /api/v1/admin/reconciliation`, `POST /api/v1/admin/accounts/{id}/freeze\|unfreeze`, `POST /api/v1/admin/standing-instructions/run` |

## How a transfer stays correct (banking-core)

1. JWT authenticates the caller; ownership of the source account is checked locally (404 for foreign accounts).
2. `Idempotency-Key` lookup: same key and payload replays the stored result (`200`, `Idempotent-Replayed: true`);
   a different payload gives `422`. A unique constraint on `(initiated_by, idempotency_key)` settles concurrent
   duplicates: the loser rolls back completely and replays the winner.
3. Fraud rules (strategy pattern) run before any lock is taken.
4. Both accounts are locked with `SELECT ... FOR UPDATE ORDER BY id`: one global lock order, so A to B and B to A
   cannot deadlock.
5. Daily limit and funds are checked under the lock; `LedgerService` writes balanced DEBIT/CREDIT entries.
6. The transfer row and the `TransferCompleted` outbox event commit in that same transaction.
7. The outbox relay (`FOR UPDATE SKIP LOCKED`) publishes to Kafka; consumers dedupe on the event id.

Money is `BigDecimal` / `NUMERIC(19,4)`. The ledger is append-only (Hibernate `@Immutable` plus a trigger).
Customer balances cannot go negative (`CHECK` constraint). Reconciliation recomputes every balance from the ledger.

## Registration across services (eventual consistency)

`auth-service` writes the user and a `UserRegistered` outbox event in one transaction. `customer-service` consumes
it idempotently and creates the profile. Until then `GET /customers/me` answers `404 PROFILE_NOT_FOUND` ("retry
shortly"). If Kafka or the customer-service is down, login still works and the profile appears when the event is
processed. The trade-off is deliberate: a synchronous auth-to-customer call would couple the availability of login
to the customer-service.

## Payments Saga (payment-service + banking-core)

States: `REQUESTED → DEBITED → SETTLED | REFUNDED`, or `REQUESTED → FAILED`; all persisted, all transitions behind
a row lock and no-ops on finished payments.

1. Persist the intent (`REQUESTED`) before any money moves.
2. `POST /internal/ledger/payment-debits` (idempotent on the payment id) → `DEBITED`. A definitive rejection
   (insufficient funds) → `FAILED`. Core unreachable → stay `REQUESTED`.
3. Call the simulated bank, with no database locks held. Accepted → `SETTLED`. Declined → compensate with
   `POST /internal/ledger/payment-reversals` (idempotent, amount taken from the stored original debit) → `REFUNDED`.
4. Bank timeout (unknown outcome) stays `DEBITED`: refunding blindly could pay the beneficiary twice.
   `PaymentRecoveryJob` re-drives every in-flight payment: it asks the bank for the real status, submits if the bank
   never saw it, and finishes the Saga.

HTTP: `201` final state, `202` still in flight (poll `GET /api/v1/payments/{id}`), `200` replay. Beneficiary
reference containing `FAIL` is declined, containing `TIMEOUT` never answers.

## Standing instructions (scheduler-service)

A daily job (06:00 Asia/Kolkata) runs due instructions through `banking-core`'s normal transfer path with the
idempotency key `SI-<id>-<runDate>`, so two scheduler instances or a retry cannot pay twice. Business rejections are
recorded and the schedule moves on; if banking-core is unreachable the run is deferred (not advanced, retried next time).

## Redis rate limiting (gateway, opt-in)

`REDIS_ENABLED=true`: `/api/v1/auth/**` 10 requests/min per client address, `POST /transfers|/payments` 20/min per
session. One Lua script, atomic across gateway instances, fails open if Redis is down. Behind an ingress the
ingress must overwrite `X-Forwarded-For`.

## Tests

```bash
mvn test        # needs Docker for the Testcontainers PostgreSQL tests
```

| Module | Unit | Integration (real PostgreSQL) |
| --- | --- | --- |
| common | JWT, internal-key filter | |
| common-data | outbox relay retries / dead-letter | |
| api-gateway | internal-path block, request id, rate limit | |
| auth-service | | register/login, refresh rotation + reuse detection, outbox event, RBAC, audit |
| customer-service | simulated KYC | `UserRegistered` handler (dedup, concurrency, poison), profile + KYC API, internal API auth |
| banking-core | ledger rules, fraud rules | transfers (concurrency, deadlock, idempotency), payment-ledger idempotency, `/internal` API, DB rules, REST contract |
| payment-service | state machine | Saga with a fake ledger: refund, timeout recovery, outages, crash recovery, idempotency |
| notification-service | handlers | dedup incl. concurrent deliveries and rollback on failure |
| scheduler-service | frequency / run keys | runs, replay, deferral, catch-up, REST lifecycle (fake banking-core) |

Cross-service behaviour is tested with in-memory fakes that honour the real contracts (`FakeLedger`, `FakeCore`)
and a mocked `CustomerClient`. There is no end-to-end test across real services yet.

## Known trade-offs

- Every deposit/withdrawal/payment locks the single system settlement account, which serialises them. Production
  fix: shard the settlement account or batch postings.
- A payment reversal posts to the customer account, so reversing onto a frozen account fails and the payment stays
  `DEBITED` until an operator unfreezes it (the recovery job keeps retrying).
- Services share one HS256 signing secret. Production should use asymmetric keys (RS256/JWKS) so only the
  auth-service can sign, and mTLS or signed tokens instead of a shared internal key.
- The `UserRegistered` event carries profile PII over Kafka; real systems would encrypt it or send a reference.
- Admin outbox/audit endpoints exist on every service but are not routed by the gateway (use the service ports).
- Service discovery is DNS (Compose / Kubernetes Services), configuration is environment variables; there is no
  Eureka or Config Server.

## Kubernetes and CI

`k8s/`: shared ConfigMap, Secret example (create real secrets out of band), a Deployment + Service per module
(rolling updates, probes, resource limits, non-root read-only containers) and HPAs for the gateway, banking-core and
payment-service. Postgres, Kafka and Redis are expected to be provided separately. `.github/workflows/ci.yml` checks
formatting, runs `mvn verify` for all modules and builds all seven images.
