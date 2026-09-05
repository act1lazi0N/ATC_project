# Aries Transaction Core

Aries Transaction Core is a learning and portfolio-grade financial backend service focused on reliable account-to-account money movement.

It is built as a Spring Boot service with realistic backend patterns: JWT authentication, transactional transfer processing, idempotency records, double-entry ledger entries, audit logs, transactional outbox events, settlement batching, and reporting reconciliation checks.

This repository is not a full production banking system. It is the transaction-core service in a broader Aries financial backend ecosystem that can later connect to reporting, settlement, fraud detection, and ML-based risk scoring services.

## Core Goals

- Move money atomically inside one database transaction.
- Prevent duplicate money movement with scoped idempotency records.
- Preserve debit-credit equality through append-only ledger entries.
- Keep account balance updates, transaction state, audit logs, ledger entries, and outbox events consistent.
- Expose reporting reconciliation as a repair-oriented check, not as the source of truth.
- Keep service boundaries microservice-ready without forcing all future services into this repository.

## Tech Stack

- Java 21
- Spring Boot 4
- Spring Web, Spring Security, Spring Data JPA
- PostgreSQL 16
- Flyway
- JJWT
- Redis dependency for future cache/rate-limit/idempotency infrastructure
- Springdoc OpenAPI
- JUnit, AssertJ, Spring Security Test, H2, Testcontainers PostgreSQL
- Docker Compose

## Domain Modules

The codebase uses package-by-domain organization under `com.actilazion.aries_transaction`:

```text
account          account creation, ownership, account status
audit            transaction audit records
common           shared DTOs and exceptions
config           security, JWT, OpenAPI, application config
identity         registration, login, users, roles
ledger           double-entry ledger posting
outbox           transactional outbox events and worker
reconciliation   reporting mismatch detection
settlement       settlement batches, items, payout attempts
transaction      transfer, reversal, refund, idempotency, state guards
```

## Main Capabilities

- User registration and login with signed JWT access tokens.
- Stateless Spring Security filter chain.
- Transfer between two accounts with pessimistic account locking.
- Ownership checks for spending and transaction history reads.
- Idempotency key handling for transfer, reversal, refund, and settlement batch creation.
- Balanced ledger entries for transfer, reversal, refund, and settlement accounting.
- Audit logs for money movement lifecycle events.
- Transactional outbox event persistence.
- Reversal and refund state guards.
- Settlement batch generation with gross, fee, and net amounts.
- Reporting reconciliation runs that classify missing, duplicate, unexpected, amount mismatch, and status mismatch cases.

## Redis ephemeral controls

Redis is advisory only; PostgreSQL remains the source of truth. Redis protects auth rate limits, login counters, and short-lived duplicate suppression for transfer, reversal, refund, and settlement commands. Redis failures bypass duplicate suppression but fail closed for authentication protection. All keys are namespaced and HMAC-derived; TTLs are bounded by configuration. The Compose Redis instance uses `noeviction` and disables persistence because these values are disposable.

## Financial Invariants

Transfer processing is synchronous and transactional. A completed transfer is expected to persist all of these in one database transaction:

- transaction record
- locked source and destination accounts
- balance validation and balance updates
- ledger debit and credit entries
- transaction status transition
- audit records
- outbox event
- completed idempotency record

Ledger entries are append-only. Old entries should not be edited to repair money movement; use reversal, refund, or compensating entries.

## API Overview

Authentication:

```text
POST /api/v1/auth/register
POST /api/v1/auth/login
```

Transfers:

```text
POST /api/v1/transfers
POST /api/v1/transfers/{id}/reverse
POST /api/v1/transfers/{id}/refund
GET  /api/v1/transfers/{id}
GET  /api/v1/transfers/account/{accountId}
```

Settlement:

```text
POST /api/v1/settlements/batches
GET  /api/v1/settlements/batches/{id}
```

Reporting reconciliation:

```text
POST /api/v1/reconciliation/runs
GET  /api/v1/reconciliation/runs/{id}
```

Swagger UI can be enabled through configuration:

```env
SPRINGDOC_API_DOCS_ENABLED=true
SPRINGDOC_SWAGGER_UI_ENABLED=true
```

Then open:

```text
http://localhost:8080/swagger-ui/index.html
```

## Configuration

The application imports `.env` automatically:

```yaml
spring.config.import=optional:file:.env[.properties]
```

Create a local env file:

```bash
cp .env.example .env
```

Required values:

```env
POSTGRES_DB=aries_transaction_db
POSTGRES_USER=transfer_user
POSTGRES_PASSWORD=your_strong_password
JWT_SECRET=replace_this_with_a_base64_256_bit_secret
```

Generate a JWT secret:

```bash
openssl rand -base64 32
```

Common optional values:

```env
JWT_ISSUER=aries-transaction
JWT_AUDIENCE=aries-transaction-api
OUTBOX_WORKER_ENABLED=false
OUTBOX_PUBLISHER=noop
OUTBOX_BATCH_SIZE=25
OUTBOX_POLL_INTERVAL_MS=5000
RECONCILIATION_EXPECTED_LAG=PT5M
RECONCILIATION_MAX_WINDOW=P31D
```

OpenAPI is disabled by default outside dev-oriented usage.

### Webhook-backed notifications

The notification module persists an in-app feed for transfer, reversal, and
refund completion events. Email delivery is a separate durable worker and is
only eligible after the recipient verifies their email. Merchant operators can
also receive endpoint-disabled and dead-lettered-delivery alerts when those
webhook lifecycle transitions are invoked.

Customer endpoints:

```text
GET   /api/v1/notifications
GET   /api/v1/notifications/unread-count
PATCH /api/v1/notifications/{id}/read
POST  /api/v1/notifications/read-all
GET   /api/v1/notifications/preferences
PUT   /api/v1/notifications/preferences
POST  /api/v1/auth/email-verification/request
POST  /api/v1/auth/email-verification/confirm
```

Operator/admin email recovery endpoints:

```text
GET  /api/v1/operations/notification-email-deliveries
POST /api/v1/operations/notification-email-deliveries/{id}/retry
```

To enable both durable webhook and notification sinks with local Mailpit:

```env
OUTBOX_WORKER_ENABLED=true
OUTBOX_PUBLISHER=fanout
WEBHOOK_FANOUT_ENABLED=true
NOTIFICATION_FANOUT_ENABLED=true
NOTIFICATION_EMAIL_MODE=smtp
NOTIFICATION_EMAIL_WORKER_ENABLED=true
EMAIL_VERIFICATION_SIGNING_KEY=<separate-base64-256-bit-key>
```

Start Mailpit with `docker compose --profile notification up -d`. The SMTP
worker is disabled by default; production startup rejects non-HTTPS public URLs
and SMTP without STARTTLS. No remote HTTP or SMTP call runs inside a money
transaction.

## Run With Docker Compose

Copy `.env.example` to `.env` and set strong values for `JWT_SECRET`,
`SECURITY_EPHEMERAL_KEY_HASH_SECRET`, and `POSTGRES_PASSWORD` before starting.

On Windows PowerShell:

```powershell
Copy-Item .env.example .env
notepad .env
```

Start PostgreSQL, Redis, and the app:

```bash
docker compose up -d
```

To rebuild the application image after source changes:

```bash
docker compose up -d --build
```

### Pull a CI-built image from GHCR

Every source change pushed to GitHub builds an image. Pushes also publish it to
GitHub Container Registry (GHCR): the default branch receives `latest`, and
every branch and commit receives immutable branch/SHA tags. Pulling a private
package requires a GitHub personal access token with `read:packages`.

On Windows PowerShell, update the running app to the latest default-branch image
without recreating PostgreSQL or Redis:

```powershell
docker login ghcr.io -u act1lazi0N
$env:APP_IMAGE = "ghcr.io/act1lazi0n/atc_project:latest"
docker compose pull app
docker compose up -d --no-deps --force-recreate app
Invoke-WebRequest http://localhost:8080/actuator/health
```

For an exact immutable CI image, replace `latest` with a `sha-<commit>` tag
shown by the GitHub Actions build.

Follow app logs:

```bash
docker compose logs -f app
```

Check health:

```text
http://localhost:8080/actuator/health
```

Stop containers while keeping volumes:

```bash
docker compose stop
```

Remove containers and data volumes:

```bash
docker compose down -v
```

## Run App Locally

Start only infrastructure:

```bash
docker compose up -d postgres redis
```

Run the app on Windows PowerShell:

```powershell
$env:SPRING_PROFILES_ACTIVE="dev"
$env:JWT_SECRET="your_base64_secret"
$env:POSTGRES_DB="aries_transaction_db"
$env:POSTGRES_USER="transfer_user"
$env:POSTGRES_PASSWORD="your_strong_password"
.\mvnw.cmd spring-boot:run
```

Run the app on macOS/Linux:

```bash
SPRING_PROFILES_ACTIVE=dev \
JWT_SECRET=your_base64_secret \
POSTGRES_DB=aries_transaction_db \
POSTGRES_USER=transfer_user \
POSTGRES_PASSWORD=your_strong_password \
./mvnw spring-boot:run
```

## Database Migrations

Flyway migrations live in:

```text
src/main/resources/db/migration
```

Current migration chain covers:

- base users, accounts, transactions, audit logs
- seed data and password hash alignment
- currency and enum-column alignment
- outbox events
- ledger entries
- transaction state guards
- idempotency records scoped by operation and initiator
- reversal/refund metadata
- settlement batches, items, payout attempts, accounting hardening
- reconciliation runs and exceptions
- merchant/operator roles and disabled demo users

Do not edit migrations that may already have run. Add a new migration such as `V22__your_change.sql`.

## Demo Data

Seed users exist only to shape local data and are disabled by migration hardening:

```text
admin@transfer.local
user_a@transfer.local
user_b@transfer.local
user_c@transfer.local
```

Create usable local users through:

```text
POST /api/v1/auth/register
```

## Testing

Run the full local test suite:

```powershell
.\mvnw.cmd test
```

On macOS/Linux:

```bash
./mvnw test
```

The suite covers service integration, security filter behavior, transaction state guards, transfer concurrency, ledger/outbox assertions, settlement, reconciliation, and Flyway migration validation.

Most integration tests use H2 in PostgreSQL compatibility mode for fast feedback. PostgreSQL-specific coverage is added with Testcontainers where it matters, including reconciliation policy behavior with Flyway-backed PostgreSQL. Testcontainers tests are skipped automatically when Docker is unavailable.

## Security Notes

- `JWT_SECRET` is required and must be a strong base64 secret.
- JWT validation checks issuer, audience, token type, signature, and expiration.
- Disabled users cannot authenticate with existing tokens.
- Swagger/OpenAPI is disabled by default outside dev usage.
- Demo users are disabled by migration hardening.
- Settlement and reconciliation operations are restricted to privileged roles in the service layer.

## Reporting and Reconciliation

Transaction Core is the source of truth. Reporting is a read model and may lag.

Reconciliation compares core transactions with reporting snapshots in an explicit completed-at window. It stores runs and exceptions instead of silently mutating source data.

Current reconciliation policy:

```text
expected lag: PT5M by default
max window:   P31D by default
```

The default reporting snapshot client is still a no-op adapter. A real reporting service client or reporting projection should replace it before this feature is treated as an end-to-end integration.

## Roadmap

Recommended next phases:

```text
1. Stabilize transaction core and authorization tests
2. Harden outbox retry and delivery semantics
3. Replace no-op reporting snapshot client
4. Expand PostgreSQL/Testcontainers coverage for locking and reconciliation
5. Mature settlement payout lifecycle
6. Add reporting backfill and repair workflows
7. Build separate aries-fraud-detection service
8. Add rule-based risk decisions before ML
```

## Repository Role

This repository should remain focused on transaction-core responsibilities. Future reporting, fraud detection, and platform orchestration can live in separate repositories such as:

```text
aries-reporting
aries-fraud-detection
aries-platform
```
