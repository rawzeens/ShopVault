# Contributing

1. Branch from `dev`; open pull requests against `dev`.
2. Run `./mvnw verify` before pushing. CI runs the same on Java 17 and 25 and builds the Docker image.
3. Fill in the security checklist in the pull request template. If a change touches `security/`,
   `auth/` or `payment/`, add or update a test that shows the protection still holds.
4. Schema changes go in a **new** Flyway migration (`V2__…sql`); never edit a released one.
5. Keep secrets out of the repository. `.env` is git-ignored; use `.env.example` for new variables.

Code style: 4-space indentation, records for DTOs, constructor injection, and no field injection in
production code.
