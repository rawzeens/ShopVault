#!/usr/bin/env bash
# Sends a correctly signed payment webhook to a running ShopVault, standing in for the payment
# provider while the simulated gateway is in use.
#
# Usage:  scripts/send-test-webhook.sh <paymentReference> <amountCents> [type] [currency]
#   type      payment.succeeded (default) | payment.failed
#   currency  MYR (default)
# Env:    SHOPVAULT_URL           default http://localhost:8080
#         PAYMENT_WEBHOOK_SECRET  default: the dev profile's sample secret
set -euo pipefail

ref="${1:?payment reference required}"
amount="${2:?amount in cents required}"
type="${3:-payment.succeeded}"
currency="${4:-MYR}"
url="${SHOPVAULT_URL:-http://localhost:8080}"
secret="${PAYMENT_WEBHOOK_SECRET:-dev-only-webhook-secret}"

[[ "$ref" =~ ^[A-Za-z0-9_.:-]+$ ]] || { echo "invalid payment reference" >&2; exit 2; }
[[ "$amount" =~ ^[0-9]+$ ]] || { echo "amount must be an integer number of cents" >&2; exit 2; }
[[ "$type" =~ ^[a-z_.]+$ ]] || { echo "invalid type" >&2; exit 2; }
[[ "$currency" =~ ^[A-Z]{3}$ ]] || { echo "currency must be 3 capital letters" >&2; exit 2; }

event_id="evt_$(openssl rand -hex 12)"
body=$(printf '{"eventId":"%s","type":"%s","paymentReference":"%s","amountCents":%s,"currency":"%s"}' \
  "$event_id" "$type" "$ref" "$amount" "$currency")
t=$(date +%s)
sig=$(printf '%s.%s' "$t" "$body" | openssl dgst -sha256 -hmac "$secret" -hex | sed 's/^.*= //')

curl -sS -o /dev/null -w "webhook $event_id -> HTTP %{http_code}\n" -X POST "$url/api/v1/payments/webhook" \
  -H 'Content-Type: application/json' -H "X-Signature: t=$t,v1=$sig" --data-binary "$body"
