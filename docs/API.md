# ShopVault API reference (v1)

Base path `/api/v1`. JSON in and out. Money is an integer in minor units (`3500` = RM 35.00), and the
currency is in each response. Authenticated calls send `Authorization: Bearer <accessToken>`.
In dev, the live OpenAPI description is at `/v3/api-docs` and Swagger UI at `/swagger-ui.html`.

## Errors

Every error is `application/problem+json`:

```json
{ "type": "about:blank", "title": "Conflict", "status": 409,
  "detail": "Not enough stock for 'House Blend 250g'", "code": "insufficient_stock" }
```

Validation errors add a list: `"errors": [{"field": "shippingAddress.country", "message": "..."}]`.

| HTTP | Typical `code` |
|---|---|
| 400 | `validation_failed`, `weak_password`, `invalid_current_password`, `password_reused`, `invalid_status`, `invalid_event`, `bad_request` |
| 401 | `unauthorized`, `invalid_token`, `invalid_credentials`, `invalid_refresh_token`, `invalid_signature` |
| 403 | `forbidden` |
| 404 | `not_found` (also returned for resources that belong to someone else) |
| 409 | `email_taken`, `sku_taken`, `slug_taken`, `cart_empty`, `cart_full`, `insufficient_stock`, `product_unavailable`, `not_cancellable`, `not_payable`, `invalid_transition`, `self_lockout`, `category_in_use`, `concurrent_update`, `conflict` |
| 411 / 413 | `length_required` / `payload_too_large` |
| 429 | `rate_limited` (with a `Retry-After` header in seconds) |
| 500 | `internal_error`. Quote the `X-Request-Id` response header when reporting it |

## Auth

### `POST /auth/register` → 201
```json
{ "email": "siti@example.com", "password": "kopi-Susu-panas-2026", "fullName": "Siti Aminah" }
```
Password: 12–128 characters, not a common or breached password, and must not contain the email name.

### `POST /auth/login` → 200
```json
{ "email": "siti@example.com", "password": "kopi-Susu-panas-2026" }
```
Register, login and refresh all return:
```json
{ "accessToken": "eyJ…", "tokenType": "Bearer", "expiresIn": 900,
  "refreshToken": "q2V…", "refreshExpiresIn": 1209600 }
```
Five failed logins lock the account for 15 minutes. All failures return the same `invalid_credentials`.

### `POST /auth/refresh` → 200
`{ "refreshToken": "…" }`. Returns a **new** token pair. The old refresh token stops working, and
using it again revokes the new one too, so always store the latest.

### `POST /auth/logout` → 204
`{ "refreshToken": "…" }`. Ends that login session.

### `POST /auth/logout-all` → 204 (auth)
Ends every session of the caller. All access tokens are rejected immediately.

## Account (auth)

| | |
|---|---|
| `GET /account/me` | `{ id, email, fullName, role, createdAt }` |
| `POST /account/password` | `{ currentPassword, newPassword }` → 204. Ends all sessions, so log in again |

## Catalogue (public)

`GET /products?q=&category=&sort=&page=&size=`
- `q` ≤ 100 characters, matched literally against the name (case-insensitive)
- `category` is a category id. `sort` is one of `NEWEST` (default), `PRICE_ASC`, `PRICE_DESC`, `NAME`
- `page` ≥ 0, `size` 1–100 (default 20)

```json
{ "content": [ { "id": "…", "sku": "COF-HOUSE-250", "name": "House Blend 250g", "description": "…",
                 "priceCents": 3500, "currency": "MYR", "inStock": true,
                 "category": { "id": "…", "name": "Coffee", "slug": "coffee" } } ],
  "page": 0, "size": 20, "totalElements": 5, "totalPages": 1 }
```

`GET /products/{id}` returns one active product. `GET /categories` lists the categories.

## Cart (auth)

| | |
|---|---|
| `GET /cart` | `{ items: [{ productId, sku, name, unitPriceCents, quantity, lineTotalCents, available }], subtotalCents, currency }` |
| `PUT /cart/items/{productId}` | `{ "quantity": 2 }` sets the quantity (1–99, ≤ stock) and returns the cart |
| `DELETE /cart/items/{productId}` | removes a line and returns the cart |
| `DELETE /cart` | empties the cart → 204 |

Prices in the cart are always live. Checkout re-reads them.

## Orders (auth)

### `POST /orders`: checkout
Headers: `Idempotency-Key: <16–64 chars of A–Z a–z 0–9 _ ->` (generate a new one per purchase attempt and
reuse it when retrying).
```json
{ "shippingAddress": { "recipientName": "Siti Aminah", "phone": "+60 12-345 6789",
    "line1": "1 Jalan Ampang", "line2": null, "city": "Kuala Lumpur", "state": "WP",
    "postcode": "50450", "country": "MY" } }
```
→ **201** with `Location` and the order, or **200** + `Idempotent-Replayed: true` for a retry.
```json
{ "id": "…", "status": "PENDING_PAYMENT", "currency": "MYR",
  "subtotalCents": 7000, "shippingCents": 1000, "totalCents": 8000,
  "shippingAddress": { … },
  "items": [ { "productId": "…", "sku": "COF-HOUSE-250", "name": "House Blend 250g",
               "unitPriceCents": 3500, "quantity": 2, "lineTotalCents": 7000 } ],
  "createdAt": "…", "paidAt": null }
```
Shipping is a flat RM 10, and free from RM 150 (configurable). Stock is held for 30 minutes until payment.

| | |
|---|---|
| `GET /orders?page=&size=` | your orders, newest first (summaries) |
| `GET /orders/{id}` | one of your orders |
| `POST /orders/{id}/cancel` | cancel an unpaid order. Stock is returned |
| `POST /orders/{id}/payment` | `{ orderId, paymentReference, checkoutUrl, amountCents, currency }`. Send the customer to `checkoutUrl` |

Status flow: `PENDING_PAYMENT → PAID → SHIPPED → DELIVERED`. Cancellation is possible from
`PENDING_PAYMENT` (customer, admin, or expiry) and from `PAID` (admin only; refund via the provider).

## Payment webhook (provider → server)

`POST /payments/webhook` with header `X-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret, t + "." + rawBody)>`
```json
{ "eventId": "evt_123", "type": "payment.succeeded", "paymentReference": "pay_…",
  "amountCents": 8000, "currency": "MYR" }
```
→ 204 once verified (also for duplicates), 401 for a bad or old signature, and 400 for a malformed body.
`scripts/send-test-webhook.sh <reference> <amountCents>` sends one signed with the dev secret.

## Admin (role ADMIN)

| | |
|---|---|
| `GET /admin/products?q=&category=&page=&size=` | includes inactive products, `stock`, `active` |
| `GET /admin/products/{id}` | |
| `POST /admin/products` | `{ sku, name, description?, priceCents, stock, categoryId?, active? }` → 201 |
| `PUT /admin/products/{id}` | same body. Price changes are audited |
| `DELETE /admin/products/{id}` | deactivates the product (kept for order history) → 204 |
| `POST /admin/categories`, `PUT /admin/categories/{id}` | `{ name, slug }` |
| `DELETE /admin/categories/{id}` | only when no product uses it |
| `GET /admin/orders?status=&page=&size=` | all orders |
| `GET /admin/orders/{id}` | |
| `PATCH /admin/orders/{id}/status` | `{ "status": "SHIPPED" \| "DELIVERED" \| "CANCELLED" }` (`PAID` is refused) |
| `GET /admin/users?page=&size=` | |
| `PATCH /admin/users/{id}` | `{ enabled?, role?, unlock? }`. Disabling or changing the role ends the user's sessions |
| `GET /admin/audit?action=&page=&size=` | security audit trail, newest first |
