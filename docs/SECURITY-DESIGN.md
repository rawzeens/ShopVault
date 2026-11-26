# Security design

This document explains *why* ShopVault is built the way it is, so changes don't undo a protection
by accident. Code references are relative to `src/main/java/com/r4xn/shopvault/`.

## Trust boundaries

```
 Browser / app ──HTTPS──> Reverse proxy (TLS) ──HTTP──> ShopVault ──> PostgreSQL
                                                            ^
 Payment provider ──HTTPS + HMAC-signed webhook─────────────┘
```

- Everything from clients is untrusted: bodies, headers, query strings, token claims (until verified).
- The payment provider is trusted **only** for messages whose HMAC signature verifies.
- The database is trusted, and is reachable only from the app (it is not published in `docker-compose.yml`).

## Authentication

**Passwords** (`security/PasswordPolicy`, `config/CoreConfig`)
- Argon2id with m=19 MiB, t=2, p=1 (the OWASP minimum), behind `DelegatingPasswordEncoder` with
  `{argon2}` prefixes. Hashes are upgraded on login if the parameters are raised later.
- BCrypt is avoided as the default because it silently ignores input past 72 bytes.
- Length 12–128. The upper bound caps hashing cost and stops "huge password" DoS. A blocklist of breached
  passwords is applied, and so is a check against the email local part. There are no composition
  rules (NIST SP 800-63B).

**Login** (`auth/AuthService#login`)
- The user row is locked (`SELECT … FOR UPDATE`) during the check, so parallel guesses are
  serialised and all of them count toward the lockout.
- After 5 failures the account is locked for 15 minutes. Wrong-password failures and lockouts commit
  even though the request fails (`noRollbackFor`).
- Unknown, locked, disabled and wrong-password logins all return the same 401 body, and a dummy
  Argon2 check runs for unknown accounts so timing does not reveal which emails exist.
- Credential endpoints are also rate limited per IP (`security/RateLimitFilter`).

**Tokens** (`security/JwtConfig`, `security/TokenService`, `security/TokenStatusValidator`)
- Access tokens: HS256 JWT, 15 minutes, with `iss`, `aud`, `sub`, `jti`, `iat`, `nbf`, `exp`, `roles`,
  and `tv` (token version). The decoder pins HS256, which rules out `alg: none` and algorithm confusion.
  It also validates issuer, audience, timestamps and `typ`.
- Revocation without a token blacklist: each user has a `token_version`, and a token whose `tv`
  differs is rejected. Logout-everywhere, password change, disabling and role change all bump it,
  so revocation is immediate. The cost is one primary-key lookup per request.
- Refresh tokens are 256-bit random values, opaque, and stored only as SHA-256 hashes. A hash is enough
  because they have full entropy, unlike passwords. Each refresh rotates the token. Presenting
  an already-rotated token is treated as theft: the whole family from that login is revoked.
- The secret must be base64 and at least 32 bytes. Startup fails otherwise, and also fails outside dev/test if a
  sample secret is used (`config/StartupSafetyCheck`).

## Authorisation

- `security/SecurityConfig` is **deny by default**: only listed public routes are open, `/api/v1/**`
  needs authentication, `/api/v1/admin/**` needs `ROLE_ADMIN`, and everything else is denied.
- Admin controllers also carry `@PreAuthorize("hasRole('ADMIN')")` (defence in depth).
- **IDOR**: customer data is always loaded with the caller's id in the query
  (`findByIdAndUserId`, cart by `userId`). Someone else's order is indistinguishable from a missing one (404).
- There is no public way to become admin. The first admin comes from `BOOTSTRAP_ADMIN_*` only while no
  admin exists. Admins cannot disable or demote themselves.

## Money, stock and orders

- Clients send only product ids, quantities and an address. **Prices, shipping and totals are
  computed server-side** from the database, and the order lines snapshot name, SKU and unit price.
- Money is `long` minor units with `Math.*Exact` arithmetic, and the database has `CHECK` constraints
  (`total = subtotal + shipping`, `line_total = unit_price * quantity`, `stock >= 0`).
- Checkout locks the product rows in ascending id order. That prevents overselling and deadlocks
  between two checkouts. Any failure rolls the whole transaction back.
- `Idempotency-Key` (unique per user in the DB) makes a retried checkout return the original order.
  If two identical requests race, the loser gets the winner's order.
- Unpaid orders expire after 30 minutes and their stock is returned. Each order is re-checked under
  a lock in case its payment just arrived.
- `PAID` can only be set by a verified webhook. Admins can move orders through fulfilment only.

## Payment webhooks (`payment/`)

- `X-Signature: t=<unix>,v1=<hex HMAC-SHA256(secret, t + "." + rawBody)>`. The raw bytes are verified
  **before** JSON parsing. The timestamp must be within 5 minutes, which stops replay, and the
  comparison is constant-time (`MessageDigest.isEqual`).
- Event ids are stored, and a redelivered event is a no-op.
- An event marks an order paid only if the **amount and currency equal the stored total**. Mismatches
  and payments for cancelled orders are recorded in the audit log for manual action (refund).

## Input, output and errors

- Every request body is a record DTO with Bean Validation. Entities are never bound from JSON, so
  extra properties cannot set roles, prices or statuses.
- JPA with bound parameters only. Search text is escaped before it is used in `LIKE`.
- Free text that ends up in logs or audit records has control characters rejected or replaced,
  which stops log forging.
- Errors are RFC 7807 `application/problem+json` with a stable `code` and a safe message. Unexpected
  exceptions are logged with the request id and answered with a generic 500. `server.error.*` never
  includes stack traces or messages.
- Bodies over 1 MB, or without a declared length, are rejected before parsing. Page sizes, cart size
  and quantities are bounded.

## HTTP layer

- Stateless: no session and no cookies. CSRF protection is therefore disabled on purpose, because a browser
  never attaches the bearer token automatically.
- Headers: `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`, `X-Frame-Options:
  DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, HSTS (1 year,
  subdomains), `Permissions-Policy`, `Cross-Origin-Opener-Policy`, `Cache-Control: no-store`.
- CORS takes an exact list of origins only (wildcards are refused at startup), and `https://` only outside dev.
  Credentials are not allowed.
- Each response carries an `X-Request-Id`. A client value is kept only if it matches a safe pattern.

## Operations

- Actuator exposes `health` and `info` only, without details. All other actuator paths are denied.
- Swagger/OpenAPI is enabled only in the dev profile.
- The container runs as a non-root UID with a read-only root filesystem and no Linux capabilities.
- The audit log (`audit_log` table plus the `AUDIT` logger) records registrations, logins, lockouts, token
  reuse, password changes, admin actions, orders and payment outcomes.
