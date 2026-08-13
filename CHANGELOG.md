# Changelog

## v2.0.0 - 2026-08-11

- Hardened identity and authentication auditing.
- Added Redis-backed auth rate limiting and login-attempt protection.
- Added short-lived duplicate suppression for money commands.
- Preserved PostgreSQL idempotency as the source of truth and replayed persisted response snapshots.
- Added Redis health/metrics configuration and safe no-eviction ephemeral deployment settings.
- Fixed Qodana findings, vulnerable Commons Compress resolution, and Linux CI Maven-wrapper execution.
