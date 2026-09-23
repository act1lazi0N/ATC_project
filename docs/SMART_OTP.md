# Smart OTP backend contract

Smart OTP approves transfers to another owner with a code generated in the separate Aries app. It is not login MFA. The backend is implemented; web/mobile clients and their OS key protection are separate delivery and acceptance gates. Default mode is `DISABLED`.

## Configuration and rollout

| Variable | Meaning |
| --- | --- |
| `SMART_OTP_MODE` | `DISABLED`, `ENROLLMENT_ONLY`, or `ENFORCED`; default `DISABLED` |
| `SMART_OTP_KEY_ID` | Identifier of the externally provisioned encryption key; 1–64 letters, digits, `_` or `-` |
| `SMART_OTP_ENCRYPTION_KEY` | Base64 of exactly 32 cryptographically random bytes; separate from JWT/reset keys |

Enabled modes fail startup if the encryption key is absent or invalid. Unknown key IDs, corrupt ciphertext and decryption failures fail closed. The current implementation accepts one key ID/key; online multi-key rotation is not implemented. Back up the key separately from the database and restore the matching pair. Never change the key in place while retaining existing credentials. Use a reviewed migration procedure for future rotation.

`DISABLED` makes OTP operations unavailable (status remains readable) and preserves the previous transfer policy. `ENROLLMENT_ONLY` permits enrollment, lifecycle operations and optional authorizations without requiring OTP for external transfers. `ENFORCED` requires a verified authorization for external transfers through preview/execute and rejects external calls to the legacy direct service method. Own-account exemption is derived from persisted ownership, never from client mode.

Roll out `DISABLED -> ENROLLMENT_ONLY -> ENFORCED` only after web/mobile enrollment, review, OTP, transfer, recovery and timeout-recovery E2E pass. Once enforced in an environment, rollback must retain enforcement or stop external transfer traffic. **Do not roll back by setting `DISABLED` or `ENROLLMENT_ONLY`, or by deploying a pre-OTP application while transfer traffic remains enabled.** The mode is an operator-controlled configuration, not a persisted irreversible policy.

All instances must share PostgreSQL, the encryption configuration and the existing Redis request-limit store. Production request protection requires `SECURITY_EPHEMERAL_ENABLED=true`. Configure the durable SMTP worker to deliver `SMART_OTP_SECURITY` emails; email transport is outside money transactions. See the existing notification/SMTP configuration for authenticated TLS deployment.

## Protocol

The fixed suite is `OCRA-1:HOTP-SHA256-8:QH64`, using [RFC 6287](https://www.rfc-editor.org/rfc/rfc6287.html). Each device has a random 256-bit secret. Codes are eight ASCII decimal digits, including leading zeroes.

1. Fetch the challenge from the configured Aries API using the app's authenticated session.
2. Base64-decode `payloadBase64` to the exact server bytes. Parse those bytes to display the purpose and, for transfers, recipient, masked account number, amount, fee, debit total, currency and description.
3. Require OS biometric/device-credential unlock before accessing the device secret.
4. SHA-256 hash the **original decoded bytes**. Its 64-character hexadecimal representation is the QH64 question. Decode that hex back to 32 bytes and right-pad to 128 bytes with zeroes for OCRA input.
5. HMAC-SHA256 input is ASCII suite, one zero byte, then the 128-byte Q field. Apply RFC dynamic truncation and reduce modulo 100,000,000; format to eight digits.

Do not reserialize JSON, trim bytes, hash the Base64 string or use 64 ASCII hex bytes as the binary Q field. The payload includes version, challenge ID, user, device, authVersion, typed purpose, random nonce and deadline. Transfer payload additionally includes preview ID, idempotency key and review fields. Foreign account UUIDs remain in a backend-only immutable binding hash.

Public interoperability fixture (test data, never a deployment secret): key bytes `0,1,...,31`, exact UTF-8 payload `{"version":1,"purpose":"TRANSFER","amount":"1500.00"}` gives `29746856`. The fixture was independently computed using .NET cryptography; Java tests also check published RFC SHA-256 vectors. This small fixture demonstrates computation only; real challenges contain all bindings above.

Backend secrets use AES-256-GCM with a fresh 12-byte nonce and AAD binding user and device UUID. The app must use Android Keystore/Apple Keychain with OS authentication; backend tests do not prove mobile hardware protection or clone resistance. Manually entered OTP is not phishing resistant.

## API

Every endpoint below requires Bearer authentication and an active, unlocked user. Requests/response errors use the existing API envelope. Responses contain `Cache-Control: no-store`. All listed paths start with `/api/v1`.

| Method/path | Request and response |
| --- | --- |
| `GET /auth/smart-otp/status` | Mode, enrollmentState (`UNAVAILABLE`, `NOT_ENROLLED`, `ACTIVE`, `RECOVERY_REQUIRED`), active deviceId |
| `POST /auth/smart-otp/enrollments` | `{currentPassword, authorizationId?, otp?, enrollmentGrant?}` -> `{id, secretBase64, challenge}` |
| `POST /auth/smart-otp/enrollments/{id}/confirm` | `{authorizationId, otp}` -> `{recoveryCodes: [...]}` |
| `POST /auth/smart-otp/management-challenges` | `{purpose}` -> challenge; only `REPLACE_DEVICE`, `REVOKE_DEVICE`, `REGENERATE_RECOVERY_CODES` |
| `POST /auth/smart-otp/devices/{id}/revoke` | `{currentPassword, authorizationId, otp}` |
| `POST /auth/smart-otp/recovery-codes/regenerate` | `{currentPassword, authorizationId, otp}` -> ten new recovery codes |
| `POST /auth/smart-otp/recover` | `{currentPassword, recoveryCode}` -> `{enrollmentGrant, expiresAt}`; revokes all sessions |
| `POST /transfers/authorizations` | `{previewId, idempotencyKey}` -> challenge |
| `GET /transfers/authorizations/{id}` | Owner-only challenge/state for app review or uncertain verification |
| `POST /transfers/authorizations/{id}/verify` | `{otp}` -> challenge/state; transfer-purpose proofs only |
| `POST /transfers` | Existing `{previewId, idempotencyKey}` plus `authorizationId` when required |

A challenge is `{id, deviceId, purpose, suite, payloadBase64, state, expiresAt}`. Initial state is `PENDING`; verification makes it `VERIFIED`; a successful operation consumes it. Reads can report `EXPIRED`, `LOCKED` or `REVOKED`. A changed authVersion invalidates outstanding proofs. Consumed state is retained as historical evidence.

Preview responses add `authorizationRequirement` (`NONE`/`SMART_OTP`) and `enrollmentState`. Money values remain decimal strings. Enrollment and replacement require the current password and verified email. Enrollment proof expires after ten minutes; transfer/management proof expires after 120 seconds, capped by preview/QR expiry for transfers.

## Device lifecycle and recovery

Only one pending and one active device per user are permitted. Initial provisioning returns the secret once; there is no read-back endpoint or secret-bearing QR. Reissuing initial enrollment abandons and erases the previous pending secret. If its response is lost, request fresh provisioning.

Replacement requires a `REPLACE_DEVICE` proof generated by the old device and the current password. The old device remains active until new enrollment confirmation. Confirmation revokes the old secret and outstanding proofs atomically. If replacement provisioning is lost, obtain a fresh proof from the still-active device.

Activation returns ten random recovery codes (256-bit entropy each); only SHA-256 hashes persist. Regeneration replaces the complete previous set. Codes are returned once; if the activation response is lost, use the now-active device and a `REGENERATE_RECOVERY_CODES` proof to issue a new set.

Recovery consumes one code, revokes active/pending devices and outstanding proofs, and increments authVersion while revoking refresh sessions. Sign in again, then use the returned user/authVersion-bound grant once within ten minutes to start enrollment. A grant cannot authorize money movement. Losing the recovery response or a consumed grant requires another unused recovery code. Remaining codes stay usable until successful activation replaces the set.

Revocation never resets `ever_enrolled`; password alone cannot reopen initial enrollment. Normal revocation preserves recovery codes. Without device or recovery codes, there is no self-service recovery in this version. Password change/reset/logout-all invalidate outstanding proofs through authVersion without deleting the active device.

## Transactions, limits and retry rules

The user row serializes OTP lifecycle and money operations. Execute locks user, active credential, preview, authorization, QR, then accounts in UUID order. OTP data uses JDBC on the same Spring-managed PostgreSQL transaction as JPA money writes. Lifecycle/cleanup also acquire the user lock first.

Verification runs in a separate transaction and returns a rejection outcome before HTTP error translation; failed attempts commit. Five incorrect codes lock a challenge. Ten failures across challenges in a rolling fifteen-minute PostgreSQL window block verification for fifteen minutes. New previews and Redis restarts cannot erase this limit. Additional shared quotas are 20 requests/user/minute and 60 requests/IP/minute across OTP endpoints. Limits return 429 with `Retry-After`; Redis failure returns 503.

Transfer authorization binds preview/key and the complete immutable private snapshot. One challenge is retained per preview; another key conflicts, and expired/locked challenges require a new preview. A verified authorization is consumed in the same commit as the preview, QR claim, transaction, balances, balanced ledger, audit, outbox and completed idempotency. Rollback restores it to `VERIFIED` for the same request until expiry. Deadlines are rechecked after waiting for account locks.

Completed idempotency replay is checked before consumed/expired OTP rejection, after authenticating and validating ownership/fingerprint. `authorizationId` and OTP are not business fingerprint dimensions. OTP-bound previews/challenges are retained, including after ordinary preview cleanup, to preserve timeout recovery. Future archival must retain equivalent owner-scoped binding and replay evidence.

For uncertain verification, read the authorization state. For uncertain execute, retain preview/key/authorization and retry the same logical request. Never silently generate a new preview or key. Client mutations use `retry:false`, submission locking and explicit unknown outcomes. Never persist OTP, provisioning secrets, recovery codes or enrollment grants in browser storage, logs or analytics.

## Errors and operations

| HTTP | Codes |
| --- | --- |
| 400 | `CURRENT_PASSWORD_INVALID`, `SMART_OTP_INVALID`, `SMART_OTP_RECOVERY_INVALID` |
| 401 | Missing/invalid/stale session or inactive/locked identity |
| 404 | `SMART_OTP_NOT_FOUND` for absent or foreign challenge/device |
| 409 | `EMAIL_VERIFICATION_REQUIRED`, `SMART_OTP_ENROLLMENT_REQUIRED`, `SMART_OTP_RECOVERY_REQUIRED`, `SMART_OTP_AUTHORIZATION_REQUIRED`, `SMART_OTP_BINDING_CONFLICT`, `SMART_OTP_PURPOSE_INVALID`, `SMART_OTP_EXPIRED`, `SMART_OTP_LOCKED`, `SMART_OTP_DEVICE_REVOKED` |
| 429 | `RATE_LIMITED`, with `Retry-After` |
| 503 | `SMART_OTP_UNAVAILABLE`, or existing security infrastructure unavailable errors |

Some verification rejections (`SMART_OTP_EXPIRED`, `SMART_OTP_DEVICE_REVOKED`, `SMART_OTP_CONSUMED`) may return 400 from the committed verification outcome. Use the stable code to choose recovery behavior.

V41 adds OTP storage, secret-state/uniqueness/purpose constraints, preview retention flag, session revocation reason and `SMART_OTP_SECURITY` email purpose. No prior migration is modified. Cleanup erases expired pending ciphertext and enrollment grants, and prunes expired failure-window rows under user locks; audit and replay records remain. Audit events contain action/challenge identifiers without secrets or OTPs. Monitor rejection/lock counts, recoveries, 503 decryption/configuration failures and SMTP queue health; avoid sensitive metric labels.

Local validation includes RFC/profile vectors, ciphertext binding, real HTTP/JWT/PostgreSQL journeys, migration upgrade, durable attempts, Redis restart, Mailpit delivery, concurrency and rollback. Full-suite results are recorded in the dated private handoff. Remote CI/CodeQL, staging deployment, real inbox delivery and web/mobile E2E require separate evidence.
