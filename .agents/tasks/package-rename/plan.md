# Package & groupId Rename — `dev.amf` → `dev.amfshr`

> **Priority:** 🔴 P1 | **Estimate:** 0.5–1 day | **Status:** Queue | **Branch:** `refactor/rename-dev-amfshr`

## Goal

Rename the Maven `groupId` from `dev.amf` → `dev.amfshr` and the Java base package
`dev.amf.budgeteer.*` → `dev.amfshr.budgeteer.*` across the whole codebase.

## Why first (before multi-module)

Do this while the project is still a **single module** — one source tree, one `pom.xml`.
Renaming after the multi-module split (#6) would mean repeating the rename across four
modules. One sweep now is far cheaper. Sequence: **this → multi-module → source migration → versioning**.

## Scope / checklist

- [ ] `pom.xml`: `<groupId>dev.amf</groupId>` → `dev.amfshr`
- [ ] Move source dirs: `src/main/java/dev/amf/budgeteer` → `src/main/java/dev/amfshr/budgeteer` (use `git mv` to preserve history); same for `src/test/java/...`
- [ ] Update every `package dev.amf.budgeteer...` declaration and `import dev.amf.budgeteer...` statement
- [ ] `logback-spring.xml` — logger names `dev.amf` → `dev.amfshr`
- [ ] `application*.properties` / YAML — any `dev.amf` references (logging levels, `@ConfigurationProperties` prefixes are annotation-based so usually unaffected — verify)
- [ ] Checkstyle config / suppressions referencing the package path
- [ ] WireMock stub packages, test resource references, `package-info.java` files
- [ ] CI workflows / scripts referencing `dev/amf` paths (e.g. `scripts/`, jacoco/coverage paths)
- [ ] Spring component scan — defaults to the `@SpringBootApplication` package, so moving the main class handles it; verify no hard-coded `dev.amf` base-package scans

## Verification

- [ ] `mvn clean verify` green (checkstyle + unit + integration)
- [ ] App boots (`./scripts/dev.sh start`) — Flyway + JPA + security filters initialise
- [ ] `grep -rn "dev\.amf\b" --include=*.java --include=*.xml --include=*.properties .` returns nothing unexpected

## Notes

- Pure rename — **no behaviour change**. Keep it in its own PR so the diff is reviewable as a mechanical move.
- Flyway migrations are unaffected (no package references in SQL).
