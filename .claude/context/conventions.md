# Conventions

> Full docs: `CONTRIBUTING.md` · `docs/testing/TESTING.md`

## Git Workflow

1. Always branch from `main` — never work directly on it
2. `git checkout main && git pull && git checkout -b <type>/<description>`
3. Push branch, open PR against `main`
4. CI (`Build & Test`) must be green before merge
5. Squash-and-merge preferred

## Branch Naming

```
<type>/<short-description>

feature/monzo-token-refresh
fix/magic-link-expiry-check
refactor/extract-monzo-client
docs/update-auth-flow
chore/upgrade-spring-boot
test/session-service-coverage
```

## Commit Format (Conventional Commits)

```
<type>(<scope>): <short description>

[optional body]
```

**Types:** `feat` `fix` `refactor` `docs` `test` `chore` `style` `perf`

**Scopes:** `auth` `monzo` `db` `api` `config` `sync` `security`

```bash
feat(monzo): implement token auto-refresh service
fix(auth): handle expired magic link tokens gracefully
test(db): add IT for MonzoConnectionRepository
chore(deps): update Spring Boot to 3.4.x
```

## Code Style (Checkstyle — Google Java Style, relaxed)

- Max line length: **120 characters**
- Max file length: **500 lines**
- Max method length: **50 lines**
- Max parameters: **7**
- No star imports, no unused imports
- camelCase methods/variables, UPPER_CASE constants
- Checkstyle config: `backend/config/checkstyle/checkstyle.xml`

## Java Conventions

- Constructor injection only (no `@Autowired` on fields)
- Controllers are thin — all logic in services
- Use DTOs for API layer, never expose entities directly
- Javadoc on all public classes and methods
- `@NullMarked` + JSpecify annotations for null safety
- Prefer `@NullMarked` at package level via `package-info.java`

## Flyway Migration Rules

- **Never modify an existing migration** — always add a new file
- Version sequentially: next is **V7**
- Naming: `V{n}__{descriptive_name}.sql` (double underscore)
- Keep migrations small and focused — one logical change per file
- Location: `backend/src/main/resources/db/migration/`
- Test locally (`mvn test -Dgroups=integration`) before committing

## Testing Conventions

- Unit tests: `*Test.java` — Mockito, no Spring context needed where possible
- Integration tests: `*IT.java` — extend `AbstractPostgresIntegrationTest`, Testcontainers
- `@WebMvcTest` for controller slice tests
- `@DataJpaTest` for repository tests
- WireMock for mocking external APIs (Monzo)
- Test class in same package as the class under test
- Aim for meaningful coverage — test behaviour, not implementation
