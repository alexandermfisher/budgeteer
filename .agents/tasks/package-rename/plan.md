# Package & groupId Rename — `dev.amf` → `dev.amfshr`

> **Priority:** 🔴 P1 | **Estimate:** 0.5 day | **Status:** ✅ Done (PR #61, merged June 2026) | **Branch:** `refactor/rename-dev-amfshr`

## Goal

Rename the Maven `groupId` `dev.amf` → `dev.amfshr` and the Java base package
`dev.amf.budgeteer.*` → `dev.amfshr.budgeteer.*` across the whole codebase. Pure rename —
**zero behaviour change**.

## Why now / why first

Do it while the project is still a **single module** (`backend/`, one `pom.xml`, one source
tree). After the multi-module split (#6) the same rename would have to be repeated across four
modules. Sequence: **this → multi-module → source migration → API versioning**.

---

## Confirmed inventory (audited 2026-06-21)

**Java — 145 files**, all under `dev/amf/budgeteer/`:
- `backend/src/main/java/dev/amf/budgeteer/` — 96 files
- `backend/src/test/java/dev/amf/budgeteer/` — 49 files

Each contains a `package dev.amf.budgeteer…` declaration and/or `import dev.amf.budgeteer…`.

**Non-Java references (7 files):**
| File | Reference |
|------|-----------|
| `backend/pom.xml` | `<groupId>dev.amf</groupId>` (line 11 — the **only** dev.amf line; no jacoco/sonar/mainClass refs) |
| `backend/src/main/resources/logback-spring.xml` | 3× `<logger name="dev.amf.budgeteer" …>` |
| `backend/src/main/resources/application-dev.properties` | `logging.level.dev.amf.budgeteer=DEBUG` |
| `backend/src/main/resources/application-prod.properties` | `logging.level.dev.amf.budgeteer=INFO` |
| `backend/src/test/resources/application-test.properties` | `logging.level.dev.amf.budgeteer=DEBUG` |
| `backend/src/test/resources/application-integration-test.properties` | `logging.level.dev.amf.budgeteer=DEBUG` |
| `backend/.idea/workspace.xml` | **gitignored — leave it; IntelliJ regenerates** |

**The one non-obvious reference** (a blanket `dev.amf.budgeteer` replace catches it, a "package/import only" replace would miss it):
- `BudgeteerApplication.java`: `@ConfigurationPropertiesScan("dev.amf.budgeteer.config")` — a **string literal** base package.

**Verified clean (no changes needed):**
- `backend/config/checkstyle/checkstyle.xml` — no package refs
- `.github/` workflows — no refs
- `scripts/` — no refs
- No `@ComponentScan` / `@EntityScan` / `@EnableJpaRepositories` / `basePackages` — component scan rides on `@SpringBootApplication` (on `BudgeteerApplication`) and moves automatically when the main class moves
- Flyway migrations (SQL) — no package refs, unaffected

---

## Execution (macOS / BSD `sed` — note the `-i ''`)

Run from repo root. Do it as **one mechanical commit**.

### Step 1 — move the source directories (preserves git history as renames)
```bash
git mv backend/src/main/java/dev/amf backend/src/main/java/dev/amfshr
git mv backend/src/test/java/dev/amf backend/src/test/java/dev/amfshr
```
> `dev/amf/` contains only `budgeteer/`, so renaming `amf` → `amfshr` yields `dev/amfshr/budgeteer/`.

### Step 2 — rewrite package / import / string-literal refs in all Java files
```bash
grep -rl 'dev\.amf\.budgeteer' backend/src --include='*.java' \
  | xargs sed -i '' 's/dev\.amf\.budgeteer/dev.amfshr.budgeteer/g'
```
> Catches `package`, `import`, the `@ConfigurationPropertiesScan("…")` literal, and `{@link dev.amf.budgeteer.*}` javadoc.

### Step 3 — pom `groupId` (note: `dev.amf`, not `dev.amf.budgeteer`)
```bash
sed -i '' 's#<groupId>dev\.amf</groupId>#<groupId>dev.amfshr</groupId>#' backend/pom.xml
```

### Step 4 — config: logback + logging levels
```bash
sed -i '' 's/dev\.amf\.budgeteer/dev.amfshr.budgeteer/g' \
  backend/src/main/resources/logback-spring.xml \
  backend/src/main/resources/application-dev.properties \
  backend/src/main/resources/application-prod.properties \
  backend/src/test/resources/application-test.properties \
  backend/src/test/resources/application-integration-test.properties
```

### Step 5 — guard: nothing left behind
```bash
grep -rn 'dev\.amf\b' backend/src backend/pom.xml backend/src/main/resources backend/src/test/resources \
  | grep -v 'dev\.amfshr'
# expected: no output
```

---

## Verification

- [ ] `cd backend && mvn clean verify` — checkstyle + unit + integration green (Docker running for ITs)
- [ ] App boots: `./scripts/dev.sh start` → Flyway runs, JPA + security filters init, no `ConfigurationProperties` bean errors (confirms the `@ConfigurationPropertiesScan` literal was updated)
- [ ] Guard grep (Step 5) returns nothing
- [ ] IntelliJ: **Invalidate Caches / Restart** (or re-import Maven) — stale `dev.amf` indexes otherwise show phantom errors
- [ ] `git diff --stat` shows ~152 files: 145 Java (renamed+edited) + pom + logback + 4 properties

## Out of scope / no impact

- **Flyway** — SQL only, untouched.
- **`backend/.idea/workspace.xml`** — gitignored; do not edit.
- **`.m2` artifact coordinate** changes (`dev.amf:budgeteer-backend` → `dev.amfshr:…`) — fine, nothing downstream consumes it.
- **No endpoint/URL changes** — that's the separate `/api/v1` task (#9).

## PR strategy

- Single PR, one squash commit, titled `refactor: rename base package/groupId dev.amf → dev.amfshr`.
- Review as a **pure mechanical move** — the diff is large but uniform; the only "logic" touch is the `@ConfigurationPropertiesScan` literal (Step 2) and the boot check covers it.
- Land before starting #6 (multi-module).

## Rollback

Branch is isolated; if `mvn verify` or boot fails, `git reset --hard` and re-run. No DB or external state involved.

## Done criteria

- `mvn clean verify` green, app boots, guard grep clean, PR merged to `main`.
- `tasks.md` #7 moved to Done; `multi-module-refactor` (#6) becomes the active head of the Queue.
