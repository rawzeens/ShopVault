# ShopVault

[![CI](https://github.com/rawzeens/ShopVault/actions/workflows/ci.yml/badge.svg?branch=dev)](https://github.com/rawzeens/ShopVault/actions/workflows/ci.yml)

A secure, API-only e-commerce server built with **Java and Spring Boot 4**. It covers the full shop
backend (accounts, catalogue, cart, checkout, payments, fulfilment and admin), and was built
security-first. Put any frontend in front of it: web, mobile, or a POS.

- **Customers**: register, log in, browse and search products, manage a cart, check out, pay, and track or cancel orders
- **Admins**: manage products, categories, orders, users and lockouts, and read the security audit log
- **Payments**: provider-agnostic, with signed webhooks. Card data never touches this server.

## Security at a glance

| Threat | What ShopVault does |
|---|---|
| Password cracking | **Argon2id** (19 MiB, t=2) via a delegating encoder, ready for future upgrades. 12–128 character passwords, a blocklist of breached passwords, no email in the password |
| Credential stuffing / brute force | Per-IP **rate limiting** (10/min on auth endpoints), **account lockout** after 5 failures. The row lock counts every parallel guess. Same answer and similar timing for unknown, wrong, locked and disabled accounts |
| Stolen tokens | **15-minute** HS256 JWT access tokens, pinned algorithm, issuer, audience and `typ` checks. **Refresh tokens rotate** on every use and are stored only as SHA-256 hashes. **Re-use of a rotated token revokes the whole session family** |
| Sessions that must end now | Logout-everywhere, password change, disable and role change all bump a token version, so every older access token is rejected on the next request |
| Broken access control / IDOR | Deny-by-default URL rules plus `@PreAuthorize` on admin controllers. Every customer query is scoped to the caller, so other people's orders return **404** |
| Mass assignment | Request DTOs (records) only contain client-settable fields. `"role":"ADMIN"` or `"totalCents":1` in a body is ignored |
| Price / stock tampering | Prices and totals are computed from the database. Stock is taken under `SELECT … FOR UPDATE` in a fixed order (no oversell, no deadlock). Order lines snapshot the price |
| Double charge / double order | Mandatory `Idempotency-Key` on checkout. Retries return the original order (`Idempotent-Replayed: true`) |
| Forged / replayed payment callbacks | HMAC-SHA256 webhook signature over `timestamp.body`, 5-minute window, constant-time compare. Event ids are stored so each one is processed once. **Amount and currency must match** the order |
| Injection | JPA with bound parameters only. Search text is escaped, so `%`, `_` and `'` are literals. Control characters are rejected in names and addresses and stripped from audit and log lines |
| Information leakage | RFC 7807 problem responses with generic messages. No stack traces, SQL or class names. Exact stock levels are hidden from the public |
| Browser attacks | Strict headers: `CSP default-src 'none'`, `X-Frame-Options DENY`, `nosniff`, `Referrer-Policy no-referrer`, HSTS, `Permissions-Policy`, `no-store` caching. CORS takes an exact list of https origins only |
| Resource exhaustion | 1 MB body cap (bodies must declare `Content-Length`), page size ≤ 100, cart ≤ 50 lines, bounded rate-limit memory, lock timeouts |
| Misconfiguration | Refuses to start with missing secrets, short or sample secrets, wildcard CORS, or `http://` origins in production. Actuator exposes only `health`/`info`. Swagger only in dev |
| Supply chain | Spring Boot dependency management, Dependabot (Maven, Actions, Docker), actions pinned to commit SHAs, CodeQL `security-extended`, Maven wrapper with a pinned checksum |
| Container | Multi-stage build, JRE-only runtime, non-root UID 10001, read-only root filesystem, all capabilities dropped, database not published to the host |

Details and the reasoning behind each choice are in [docs/SECURITY-DESIGN.md](docs/SECURITY-DESIGN.md).

## Tech stack

Java 17+ (CI tests 17 and 25) · Spring Boot 4.2 · Spring Security 7 (OAuth2 resource server, Nimbus JOSE) ·
Spring Data JPA / Hibernate 7 · Flyway · PostgreSQL (H2 for dev/tests) · Caffeine · springdoc-openapi · JUnit 5 + MockMvc

## Quick start (local, no database needed)

```bash
./mvnw -Pdev spring-boot:run
```

- API: <http://localhost:8080/api/v1/products>
- Swagger UI: <http://localhost:8080/swagger-ui.html>
- Dev admin: `admin@shopvault.dev` / `dev-only-Shop-Owner-2026` (dev profile only, and refused by every other profile)
- A demo catalogue is seeded. The data lives in memory and resets on restart.

### A full purchase with curl

```bash
B=http://localhost:8080; H='Content-Type: application/json'

# 1. register (returns accessToken + refreshToken)
TOKEN=$(curl -s -X POST $B/api/v1/auth/register -H "$H" \
  -d '{"email":"siti@example.com","password":"kopi-Susu-panas-2026","fullName":"Siti Aminah"}' | jq -r .accessToken)

# 2. pick a product and put 2 in the cart
PRODUCT=$(curl -s "$B/api/v1/products?q=house" | jq -r '.content[0].id')
curl -s -X PUT $B/api/v1/cart/items/$PRODUCT -H "$H" -H "Authorization: Bearer $TOKEN" -d '{"quantity":2}'

# 3. check out (prices come from the server; the key makes retries safe)
ORDER=$(curl -s -X POST $B/api/v1/orders -H "$H" -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"shippingAddress":{"recipientName":"Siti Aminah","phone":"+60 12-345 6789","line1":"1 Jalan Ampang",
       "city":"Kuala Lumpur","state":"WP","postcode":"50450","country":"MY"}}' | jq -r .id)

# 4. open a payment, then play the payment provider and confirm it
PAY=$(curl -s -X POST $B/api/v1/orders/$ORDER/payment -H "Authorization: Bearer $TOKEN")
scripts/send-test-webhook.sh "$(echo $PAY | jq -r .paymentReference)" "$(echo $PAY | jq -r .amountCents)"

# 5. the order is now PAID
curl -s $B/api/v1/orders/$ORDER -H "Authorization: Bearer $TOKEN" | jq .status
```

## Run in production

1. Put it behind a TLS-terminating reverse proxy (nginx, Caddy, a cloud load balancer). Never serve the API over plain HTTP.
2. `cp .env.example .env` and fill in every value. Generate secrets with `openssl rand -base64 48`.
3. `docker compose up --build -d` starts PostgreSQL and the app, published on `127.0.0.1:8080` for the proxy.
4. After the first start, log in as the bootstrap admin, then **remove** `BOOTSTRAP_ADMIN_*` from `.env`.

| Variable | Required | Purpose |
|---|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | yes | PostgreSQL connection (`DB_URL` is set by compose) |
| `JWT_SECRET` | yes | Base64 HMAC key, ≥ 32 bytes. Rotating it logs everyone out |
| `PAYMENT_WEBHOOK_SECRET` | yes | Shared secret used to verify provider webhooks (≥ 32 chars) |
| `CORS_ALLOWED_ORIGINS` | for browsers | Comma-separated exact `https://` origins of your frontends |
| `BOOTSTRAP_ADMIN_EMAIL` / `_PASSWORD` | first start | Creates the first admin if none exists |
| `PAYMENT_PROVIDER`, `PAYMENT_CHECKOUT_BASE_URL` | no | `simulated` until you add a real gateway |
| `FORWARD_HEADERS_STRATEGY` | no | `native` (default) trusts `X-Forwarded-*` from private-network proxies only. Use `none` without a proxy |
| `SHOP_CURRENCY`, `JWT_ISSUER`, `JWT_AUDIENCE`, `PORT`, `DB_POOL_SIZE` | no | Tuning |

Running several replicas? The rate limiter is per instance. Add rate limiting at the edge too (load
balancer or WAF), or move the buckets to Redis.

## Going live with a real payment provider

`SimulatedPaymentGateway` stands in until you connect a provider (Stripe, Billplz, ToyyibPay, iPay88, …):

1. Implement `payment/PaymentGateway`. `createPayment` calls the provider's API with the **stored order
   total** and returns its payment id. `checkoutUrl` returns the hosted payment page.
2. Annotate it with `@ConditionalOnProperty(name = "app.payments.provider", havingValue = "yourprovider")`
   and set `PAYMENT_PROVIDER=yourprovider`.
3. Point the provider's webhook at `POST /api/v1/payments/webhook`. If its signature scheme differs from
   `X-Signature: t=…,v1=…`, adapt `WebhookSignatureVerifier`, and keep the timestamp window and the
   constant-time compare.

## API overview

Full reference: [docs/API.md](docs/API.md) (and Swagger UI in dev). All money is in minor units (sen/cents).

| | Endpoint | Who |
|---|---|---|
| Auth | `POST /api/v1/auth/register` · `login` · `refresh` · `logout` · `logout-all` | public / user |
| Account | `GET /api/v1/account/me` · `POST /api/v1/account/password` | user |
| Catalogue | `GET /api/v1/products` (`q`, `category`, `sort`, `page`, `size`) · `GET /api/v1/products/{id}` · `GET /api/v1/categories` | public |
| Cart | `GET/DELETE /api/v1/cart` · `PUT/DELETE /api/v1/cart/items/{productId}` | user |
| Orders | `POST /api/v1/orders` (checkout) · `GET /api/v1/orders[/{id}]` · `POST /api/v1/orders/{id}/cancel` · `POST /api/v1/orders/{id}/payment` | user |
| Payments | `POST /api/v1/payments/webhook` | provider (HMAC) |
| Admin | `/api/v1/admin/products` · `/categories` · `/orders` (+ `PATCH /{id}/status`) · `/users` · `/audit` | admin |
| Health | `GET /actuator/health` (`/liveness`, `/readiness`) | public |

## Project layout

```
src/main/java/com/r4xn/shopvault/
├── auth/       register, login, refresh rotation, logout, password change
├── security/   filter chain, JWT config, token revocation, rate limiting, password policy
├── user/       accounts and admin user management
├── catalog/    products and categories
├── cart/       per-user cart
├── order/      checkout, order lifecycle, expiry of unpaid orders
├── payment/    gateway port, simulated gateway, signed webhooks
├── audit/      security audit trail
├── common/     error handling (RFC 7807), paging
└── config/     typed configuration, startup safety checks, bootstrap admin, dev data
src/main/resources/db/migration/   Flyway schema
src/test/java/                     integration tests through the real HTTP + security stack
```

## Testing

```bash
./mvnw verify
```

There are 59 tests. Most are full-stack integration tests (MockMvc, the real security filter chain,
H2 in PostgreSQL mode). Two more run the purchase flow on **real PostgreSQL** via Testcontainers
whenever Docker is available, which is always the case in CI. They cover the lockout, token rotation and reuse detection, revocation, forged,
`alg:none` and wrong-audience JWTs, IDOR, mass assignment, price tampering, idempotent checkout, stock
locking (8 parallel buyers for 5 units never oversell) and restock, webhook signatures, replay and amount checks, rate limiting, CORS, security
headers, body limits, LIKE-injection and error leakage.

## Not included yet

Email verification and password reset (they need an email provider), MFA/TOTP for admins, and
distributed rate limiting. These are tracked as good next steps in [SECURITY.md](SECURITY.md).
