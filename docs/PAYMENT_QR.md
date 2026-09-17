# Internal Aries payment QR

The backend supports reusable account QR codes and fixed, single-payment requests. This slice uses the existing authenticated preview/execute flow. Smart OTP, VietQR, QR login, image rendering and camera decoding are separate work.

## Payload and lifecycle

The client renders exactly `aries:pay:v1:<canonical-lowercase-UUID-v4>` as a QR image. The random identifier locates a backend record; it never authorizes a debit. Resolve requires a valid access JWT. Only this ASCII format is accepted, with a 256-byte maximum; URLs and remote content are never fetched.

| Type | Values | Lifetime | Successful use |
| --- | --- | --- | --- |
| `ACCOUNT` | Payer supplies amount and optional description | Until owner revokes | Reusable, one active QR per account |
| `PAYMENT_REQUEST` | Receiver fixes amount and optional description | 15 minutes from creation | Once |

Stored states are `ACTIVE`, `PAID`, `REVOKED`. An active request at or past `expiresAt` is returned as `EXPIRED`; expiry uses server time. Revoke is idempotent for a revoked code and may close an expired request. A paid request cannot be revoked, and refund/reversal does not reactivate it.

All amounts are VND decimal strings, normalized to two fractional digits. Existing transfer policy applies: minimum `1000.00`, maximum 16 integer digits, at most two fractional digits. ACCOUNT creation omits amount and description. Creating a payment request requires amount. All creation requests require `currency: "VND"`.

## HTTP contracts

All paths are relative to `/api/v1`; all require `Authorization: Bearer <access-token>`. Responses use the existing `ApiResponse<T>` envelope. QR responses include `Cache-Control: no-store`.

| Method | Path | Authorization / response |
| --- | --- | --- |
| POST | `/accounts/{accountId}/qr-codes` | Account owner only; requires `Idempotency-Key`, returns QR metadata |
| GET | `/accounts/{accountId}/qr-codes?page=0&size=20` | Account owner only; page size 1–100, ordered newest first then ID |
| POST | `/qr-codes/{id}/revoke` | QR owner only, returns current metadata |
| POST | `/qr-codes/resolve` | Authenticated caller, returns masked recipient and authoritative payment values |
| POST | `/transfers/preview` | Authenticated payer owns source account; accepts exclusive `qrCodeId` selector |
| POST | `/transfers` | Existing `previewId` and `idempotencyKey` execution |

Operators/admins have no QR ownership override. Unknown/foreign QR management targets return the same `404 QR_UNAVAILABLE`. Owners may list/revoke codes even when the receiving account is frozen. Creation and resolution require an active VND account and active recipient user.

Creation returns HTTP 200 for both first creation and replay. The creation key contains 1–64 nonblank characters and is scoped to the authenticated owner across accounts. Same key and normalized request returns the original QR identifier and immutable values with its **current** lifecycle metadata; another request conflicts. A different creation key on an account that already has an active ACCOUNT QR returns `QR_ACCOUNT_EXISTS`; list the account's codes or revoke before replacement. The key is distinct from the later transfer execution key. Browser CORS permits the `Idempotency-Key` header.

Example payment-request creation body:

```json
{"type":"PAYMENT_REQUEST","amount":"1500.00","currency":"VND","description":"Lunch"}
```

Account QR creation body:

```json
{"type":"ACCOUNT","currency":"VND"}
```

Owner metadata fields: `id`, `payload`, `type`, `state`, `amount`, `currency`, `description`, `createdAt`, `expiresAt`, `transactionId`. Optional fields may be null. `transactionId` is populated for paid requests only. No provisioning or authentication secret is present.

Resolve body (replace placeholder with the returned payload):

```json
{"payload":"aries:pay:v1:<qr-id>"}
```

Resolve data fields: `qrCodeId`, `type`, `recipient: {accountNumberMasked, displayName}`, `amount`, `currency`, `description`, `expiresAt`. It does not reveal recipient account/user UUIDs, full account numbers, balances or successful transaction IDs to the payer. Inactive/expired/paid/revoked codes cannot resolve for a new payment.

Fixed-payment preview body:

```json
{"sourceAccountId":"<owned-account-id>","qrCodeId":"<resolved-qr-id>"}
```

For PAYMENT_REQUEST previews, omit `amount` and `description`; any supplied non-null value is rejected, including an identical value. Currency may be omitted or supplied as VND. For ACCOUNT previews, supply the payer's amount and optional description:

```json
{"sourceAccountId":"<owned-account-id>","qrCodeId":"<resolved-qr-id>","amount":"2000.00","description":"Thanks"}
```

Do not combine `qrCodeId` with `toAccountId` or `recipientAccountNumber`. Mode is derived from database ownership; if supplied, it must match. Existing manual/own-account requests retain their required mode, amount and currency. Preview output retains the existing masked parties, exact amount/fee/debitTotal, currency and expiry. Effective expiry is the earlier of the normal preview deadline and QR deadline. A preview does not reserve a request: another payer can complete first.

Execution body remains:

```json
{"previewId":"<preview-id>","idempotencyKey":"<stable-16-to-64-character-key>"}
```

## Errors and client recovery

| HTTP | Code | Meaning |
| --- | --- | --- |
| 400 | `BAD_REQUEST` / `VALIDATION_ERROR` | Invalid payload, missing creation key, invalid selectors or fixed-value override |
| 401 | Authentication rejection | Missing/invalid/revoked token or suspended caller |
| 403 | `FORBIDDEN_OPERATION` / `FORBIDDEN` | Foreign transfer source/preview, according to existing transfer policy |
| 404 | `QR_UNAVAILABLE` | Unknown QR/management target or unavailable receiving account |
| 409 | `QR_ACCOUNT_EXISTS` | Active reusable code already exists |
| 409 | `QR_EXPIRED`, `QR_REVOKED`, `QR_PAID` | Code cannot start another payment |
| 409 | `IDEMPOTENCY_CONFLICT` | Key reused for a different request |
| 422 | `INVALID_TRANSFER_AMOUNT` | Existing transfer amount policy violation |
| 429 | `RATE_LIMITED` | Quota reached; observe `Retry-After` |

Existing preview/account/currency/funds errors also apply. If a preview itself expires first, execution reports the existing preview error rather than a QR-specific error.

QR endpoints share quotas of 30 requests per authenticated user and 120 per resolved client IP per minute, using the existing rate-limit store and hashed keys. The production Redis store fails closed with 503 on infrastructure failure. Transfer preview retains its existing independent quotas.

After an unknown execute result, keep the same preview and key and retry the same logical request. Do not automatically generate another key or preview. A completed matching idempotency result is returned before current QR state or expiry is rejected. Use `retry: false` for automatic client mutation retries, disable duplicate submissions, and allow explicit same-request recovery. Creation retries likewise preserve the original creation key/body. Display receiver and backend amount before submitting; do not treat scanning as consent to debit.

Consumed QR-bound previews and QR records are retained as durable replay/audit evidence. Existing cleanup removes expired unconsumed QR previews but preserves consumed QR previews, including account-QR payments. This increases retained metadata; a future archive policy must preserve equivalent owner-scoped replay bindings. Non-QR preview retention remains unchanged.

## Transaction and storage guarantees

V40 adds QR storage, partial uniqueness for active account QR, owner-scoped creation key uniqueness, state/value constraints, unique successful transaction reference and preview linkage. Creation serializes on the owner and receiving account. Execution locks the initiator, then preview, then QR, then both accounts in the core's UUID order. Preview locking does not fetch/lock accounts through a join. Revocation locks owner then QR and does not acquire account locks.

PAYMENT_REQUEST consumption, preview consumption, transaction, balance changes, balanced ledger, audit, outbox and completed idempotency commit together. QR validity is rechecked after waiting for account locks; expiry during the wait rolls everything back. Lifecycle audit records contain QR ID/type/state and successful transaction reference without QR payload or credentials. Direct service transfers carrying a preview ID must use the execute path so QR consumption cannot be bypassed.

## Verification

```powershell
.\mvnw.cmd '-Dtest=QrDomainTest,QrPostgresIntegrationTest,QrMigrationIntegrationTest' test
.\mvnw.cmd clean verify
```

Requires Java 21 through `JAVA_HOME`, Maven dependency access and a running Docker Engine. QR integration tests use PostgreSQL 16 with real Flyway migrations and random-port HTTP through the JWT filter chain. They cover competing payers, opposite transfers, rollback, pay/revoke races, frozen accounts, insufficient funds, immutable preview values, cleanup/retry, reversal, ownership, masking, CORS and rate limiting. Migration tests upgrade existing V39 previews and exercise database constraints. Tests do not establish browser/mobile camera or staging deployment readiness.
