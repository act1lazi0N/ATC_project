# Account security: backend contract and rollout

The backend supports changing a password, recovering a forgotten password by email,
and revoking every session. This feature is disabled by default. It does not add a
session/device management UI or change financial transaction processing.

## API contract

All endpoints use the existing `ApiResponse` envelope. Successful responses and
errors carry `Cache-Control: no-store`. Passwords must contain at least 8 characters
and at most 72 UTF-8 bytes, are never trimmed, and must differ from the current
password. BCrypt remains the password encoder.

| Method and path | Authentication | JSON body | Success |
| --- | --- | --- | --- |
| `POST /api/v1/auth/change-password` | Access Bearer token | `currentPassword`, `newPassword` | 200; sign in again |
| `POST /api/v1/auth/forgot-password` | Public; omit Authorization | `email` | 202; generic acceptance |
| `POST /api/v1/auth/reset-password` | Public; omit Authorization | `token`, `newPassword` | 200; sign in again |
| `POST /api/v1/auth/logout-all` | Access Bearer token | None | 200; sign in again |

Change, reset and logout-all require an `Origin` allowed by
`AUTH_REFRESH_ALLOWED_ORIGINS`, following the existing refresh-cookie policy. If
`Sec-Fetch-Site` is present it must be `same-origin` or `same-site`. They clear the
`refresh_token` cookie with the same path (`/api/v1/auth`), Secure, HttpOnly and
SameSite attributes used during login. They never issue a new access or refresh token.
The usual logout endpoint continues to revoke only its refresh family, including
when an accompanying access token is stale.

Forgot-password always replies:

> If an eligible account exists, password reset instructions will be emailed.

An eligible account is active. Its email may be unverified, and temporary login
lockout does not prevent recovery. Suspended and unknown accounts receive the same
response without a reset delivery. Requesting recovery never changes a password,
revokes a session, locks an account or marks its email verified. A successful reset
clears temporary login lockout, but never reactivates a suspended account or marks
the email verified. Suspension between requesting and consuming a link invalidates
its use while the account is suspended.

| Status / code | Meaning |
| --- | --- |
| 400 `CURRENT_PASSWORD_INVALID` | Current password does not match |
| 400 `PASSWORD_UNCHANGED` | New password matches the current password |
| 400 `PASSWORD_RESET_TOKEN_INVALID` | Reset token is invalid, expired, replaced, or consumed |
| 400 `VALIDATION_ERROR` | Required field, shape or password policy failure |
| 401, `WWW-Authenticate: Bearer` | Missing, invalid, or revoked access credentials |
| 403 `CSRF_ORIGIN` | Origin/fetch-site check failed |
| 429 `RATE_LIMITED`, `Retry-After` | Request limit exceeded |
| 503 `FEATURE_DISABLED` | Account security has not been enabled |

The feature flag is checked on valid new operations; authentication and DTO
validation can reject malformed or unauthorized requests before this check.

## Session and transaction behavior

`users.auth_version` is independent of JPA optimistic-lock `version`. New access
tokens include `authVersion`. Each authenticated request compares the signed value
with the current database value. A legacy signed token with no claim is treated as
version zero; a malformed or negative claim is rejected.

Change/reset/logout-all increase `auth_version` and revoke every active refresh
session atomically. Requests authenticated after that commit cannot use old tokens.
Already authenticated in-flight financial operations are not cancelled. Login,
refresh and logout share a user-first lock order with password operations. A late
refresh response can still overwrite a browser cookie, but the contained session
is revoked on the server and cannot restore access. A new login after revocation is
a new valid session; retrying an old logout-all credential cannot revoke it.

Password changes, reset challenge consumption/invalidation, session revocation,
success audit and security email enqueue share one database transaction. A failure
in any persistence step rolls them all back. Success audit uses a transaction-joining
method; rejection audit can persist independently. SMTP runs outside the transaction.
Temporary login-attempt counters also exist in the ephemeral store: clearing a
counter is not a distributed transaction with PostgreSQL, and database lockout state
remains authoritative if the password transaction rolls back.

Reset challenges expire after 15 minutes by default. Each accepted resend after
the cooldown invalidates the earlier challenge. Tokens are HMAC-SHA256 signatures
bound to the purpose, random challenge UUID, user UUID, email snapshot and expiry.
They use a separate 256-bit key, are recreated only for email rendering, and are not
stored as plaintext. The verification and reset token mechanisms are distinct.

## Email and retention

`email_deliveries.purpose` now also contains `PASSWORD_RESET` and `PASSWORD_CHANGED`.
The first references a reset challenge; the second references the committed identity
audit event. Both use the existing durable SMTP worker, attempts, claim fencing,
retry limits and operations queue. They do not use transaction email preferences or
introduce new in-app notification types.

`202` means accepted, a `PENDING` delivery means queued, and `DELIVERED` records SMTP
acceptance. None alone proves receipt in an external inbox. SMTP outcome uncertainty
continues to use `DELIVERY_OUTCOME_UNKNOWN`. Expired, consumed, replaced or ineligible
reset challenges are rechecked at claim; unusable reset dead letters cannot be
redriven. An email already handed to SMTP cannot be recalled, but consuming its
token still requires an eligible, unconsumed challenge.

Hourly cleanup cancels unusable reset deliveries in PENDING/FAILED/DEAD_LETTERED.
PROCESSING deliveries retain their lease/fencing and recover through the worker.
Security deliveries in DELIVERED/CANCELLED, and their terminal challenges, are
retained at least 30 days before deletion. Unresolved deliveries are preserved.
Audit history is not deleted by this cleanup.

## Configuration and release order

See `.env.example` and Compose for the complete settings. Essential values:

```dotenv
ACCOUNT_SECURITY_ENABLED=false
PASSWORD_RESET_SIGNING_KEY=<separate generated Base64 key of at least 32 random bytes>
PASSWORD_RESET_PUBLIC_URL=https://your-web-host/reset-password
PASSWORD_RESET_TTL=PT15M
```

Never reuse the JWT or email verification signing key. The reset URL is configured
by the operator; request Host headers cannot select it. Enabled configuration rejects
missing/weak reset keys, relative URLs, credentials, query strings, fragments, and
non-HTTPS production URLs. Configure SMTP and enable
`NOTIFICATION_EMAIL_MODE=smtp` / `NOTIFICATION_EMAIL_WORKER_ENABLED=true` for actual
delivery. Enabling the APIs while the worker is paused only queues mail.

Default forgot limits are 3 requests/email and 20/IP per 15 minutes, with a 60-second
send cooldown and a 200-ms minimum ordinary response duration. Cooldown requests
still receive generic acceptance, while exceeded request limits receive 429.
Counters include unknown identities. Reset/change/logout-all use the existing
per-operation auth limiter (60/IP and 10/identity per minute by default). Reset
tokens are hashed in their exact case before entering that limiter. Minimum response
padding is not a guarantee of identical latency under database contention.

1. Apply V39 and deploy the version-aware backend on every instance with the flag off.
2. Update frontend parsers/labels to accept both new delivery purposes. Build the
   recovery routes and settings actions against this contract in the frontend phase.
3. Configure the independent reset key, trusted reset URL, SMTP and cookie origins.
4. Verify the flow in an isolated test environment, then enable account security.
5. Monitor reset abuse, rate-limit responses, queued/failed/dead-letter email, expired
   resets and session rejections using existing audit and delivery operations views.

Do not roll back to a backend that ignores `authVersion` after using revocation.
Disabling the flag does not undo completed revocations. Keep the reset signing key
and email configuration available while existing mail is being resolved. Compose
environment changes require recreating the app; restarting alone does not replace
its environment.

## Frontend handoff

- Add public `/forgot-password` and `/reset-password` routes. Read `?token=` once,
  remove it with history replacement, and keep it only in local transient form state.
  Set `Referrer-Policy: no-referrer`; do not send it to analytics, persistent stores,
  query keys, or error reporting. Opening a link/GET must not consume it.
- Ask for password confirmation in the UI; submit only the fields in the API table.
  Public recovery calls must omit stale Authorization headers.
- Settings change-password requires the current password; logout-all uses the
  authenticated user and accepts no user ID. Explain that both include this device.
- On success clear access state and user-scoped query data, notify other local tabs
  using the existing session mechanism, and return to sign-in without auto-login.
- Use `retry: false` and a synchronous submission lock for these mutations. Network
  failure after submission has an unknown outcome: do not replay automatically.
  For password operations, recover through normal sign-in or a fresh reset request;
  for logout-all, verify session state before presenting success.
- Extend the email operations purpose parser and labels before the backend flag is
  enabled. Continue treating 202 as accepted/queued and ambiguous sends as unknown.

## Validation commands

```powershell
.\mvnw.cmd -q "-Dtest=AccountSecurityPostgresIntegrationTest,PasswordPolicyAndTokenTest,PasswordRequestProtectionTest,AccountSecurityConfigurationTest" test
.\mvnw.cmd -q "-Dtest=AccountSecurityMigrationIntegrationTest,PasswordRecoveryMailpitIntegrationTest" test
.\mvnw.cmd clean verify
```

Tests use isolated PostgreSQL/Testcontainers and the real security filter chain.
The Mailpit test starts `axllent/mailpit:v1.27` on random ports, runs the real SMTP
gateway/worker, reads the captured reset link through the
[Mailpit API](https://mailpit.axllent.org/docs/api-v1/), consumes it through MockMvc,
checks revocation, and captures the password-change confirmation. It sends only to
synthetic addresses in that isolated inbox. It does not use the local Compose app
or a real recipient's mailbox. Frontend/browser integration and deployment are
separate evidence.
