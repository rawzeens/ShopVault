# Security Policy

## Reporting a vulnerability

Please **do not open a public issue**. Report privately through
[GitHub Security Advisories](https://github.com/rawzeens/ShopVault/security/advisories/new) with:

- what is affected (endpoint, file, version or commit),
- steps to reproduce or a proof of concept,
- the impact you expect.

You will get an acknowledgement within 3 working days and a status update at least weekly until the
issue is fixed. Please give us reasonable time to release a fix before any public disclosure.

## Supported versions

Only the latest commit on the default branch receives security fixes.

## Scope

In scope: this server's code, configuration defaults, Dockerfile and compose file.
Out of scope: denial of service by sheer volume (use an edge rate limiter / WAF), findings that
require a compromised host or leaked secrets, and the simulated payment gateway (it is a stand-in
by design).

## Operator checklist

Before taking real orders:

- [ ] Served only over HTTPS through a reverse proxy; `FORWARD_HEADERS_STRATEGY` matches the setup
- [ ] `JWT_SECRET` and `PAYMENT_WEBHOOK_SECRET` freshly generated (`openssl rand -base64 48`) and stored in a secret manager
- [ ] `BOOTSTRAP_ADMIN_*` removed after the first admin exists
- [ ] A real `PaymentGateway` configured (the simulated one logs a warning at startup)
- [ ] PostgreSQL not reachable from the internet; backups enabled and tested
- [ ] `CORS_ALLOWED_ORIGINS` lists only your frontends
- [ ] Logs (including the `AUDIT` logger) shipped somewhere you will actually look
- [ ] Dependabot alerts and security updates enabled on the repository

## Known gaps / roadmap

- Email verification and password reset flows
- TOTP multi-factor authentication for admin accounts
- Distributed (Redis) rate limiting for multi-instance deployments
- Asymmetric (RS256/EdDSA) token signing if other services need to verify tokens
