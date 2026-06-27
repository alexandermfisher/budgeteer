# Multi-Module Maven Restructure

**Status:** Queue  
**Priority:** P1  
**Estimate:** 1d  
**Branch:** `refactor/multi-module-maven`

## Goal

Convert the current single-module Maven project into a four-module structure. No source code moves in this task — this is pure scaffolding and renaming. Source migration (moving Monzo code into `monzo-client`, TrueLayer implementation) is a separate task.

---

## Final Module Structure

```
budgeteer/                        ← budgeteer-parent (root POM)
├── budgeteer-common/             ← budgeteer-common (shared utilities jar)
├── monzo-client/                 ← monzo-client (Monzo integration jar)
├── truelayer-client/             ← truelayer-client (TrueLayer integration jar)
└── budgeteer-api/                ← budgeteer-api (Spring Boot app, renamed from backend/)
```

## POM Hierarchy

```
spring-boot-starter-parent
    └── budgeteer-parent  (root pom.xml)
            ├── budgeteer-common
            ├── monzo-client
            ├── truelayer-client
            └── budgeteer-api
```

Root POM parents from `spring-boot-starter-parent` — all modules inherit Spring Boot's dependency management.  
`spring-boot-maven-plugin` (executable fat jar) configured only in `budgeteer-api`.

---

## Implementation Steps

### Step 1 — Update root `pom.xml`
- Add `spring-boot-starter-parent` as `<parent>`
- Move shared properties here (`java.version`, `lombok.version`, `testcontainers.version`)
- Add all four `<module>` declarations:
  ```xml
  <modules>
      <module>budgeteer-common</module>
      <module>monzo-client</module>
      <module>truelayer-client</module>
      <module>budgeteer-api</module>
  </modules>
  ```

### Step 2 — Rename `backend/` → `budgeteer-api/`
- `git mv backend budgeteer-api`
- Update `budgeteer-api/pom.xml`:
  - `<parent>` → `budgeteer-parent` (remove `spring-boot-starter-parent` direct parent)
  - `<artifactId>budgeteer-backend</artifactId>` → `budgeteer-api`
  - `<name>Backend</name>` → `Budgeteer API`
  - Remove properties now declared in root POM

### Step 3 — Create `budgeteer-common` stub
Minimal `pom.xml`, parents from `budgeteer-parent`, no source yet.  
Will eventually hold: `OAuthStateService`, `OAuthState` entity, `OAuthProvider` enum, `EncryptionService`, `TokenResponse`.

### Step 4 — Create `monzo-client` stub
Minimal `pom.xml`, parents from `budgeteer-parent`, no source yet.  
Will eventually hold: `MonzoClient`, `MonzoOAuthService`, `MonzoConnection` entity + services, token refresh.

### Step 5 — Create `truelayer-client` stub
Minimal `pom.xml`, parents from `budgeteer-parent`, no source yet.  
Will eventually hold: `TrueLayerClient`, `TrueLayerOAuthService`, `TrueLayerConnection` entity + services.

### Step 6 — Update references to `backend/`

| File | Change |
|------|--------|
| `scripts/dev.sh` | 5 × `cd "$PROJECT_ROOT/backend"` → `budgeteer-api` |
| `.github/workflows/ci.yml` | 4 references |
| `.github/workflows/codeql.yml` | 2 references |
| `.github/dependabot.yml` | `directory: "/backend"` → `/budgeteer-api` |
| `.github/CODEOWNERS` | `/backend/` → `/budgeteer-api/` |

### Step 7 — Verify
- `mvn clean verify` from root passes
- CI green on PR

---

## Notes

- Flyway migrations remain centralised in `budgeteer-api/src/main/resources/db/migration/` — not distributed to integration jars
- This task is scaffolding only. Source code migration (moving `OAuthStateService`, `MonzoClient` etc. into their new modules) is a follow-on task
- Follow-on task: **OAuth Abstraction & Source Migration** — extract `OAuthStateService` into `budgeteer-common`, move Monzo code into `monzo-client`, implement TrueLayer OAuth in `truelayer-client`
