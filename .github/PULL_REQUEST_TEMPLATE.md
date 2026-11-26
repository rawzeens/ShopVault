## What does this change?

<!-- One or two sentences. Link the issue if there is one: Closes #123 -->

## Security checklist

- [ ] New endpoints are covered by a rule in `SecurityConfig` (deny by default) and, for admin
      endpoints, `@PreAuthorize("hasRole('ADMIN')")`
- [ ] Every query that returns user data is scoped to the caller (no IDOR)
- [ ] Request bodies are DTOs with Bean Validation; no entity is bound from JSON
- [ ] No secrets, tokens, passwords or personal data in logs, errors or responses
- [ ] Money and stock changes happen server-side, under a lock or version check
- [ ] Security-relevant actions are written to the audit log
- [ ] Tests added or updated (`./mvnw verify` passes)

## How was it tested?
