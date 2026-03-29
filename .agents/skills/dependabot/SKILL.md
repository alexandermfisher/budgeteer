---
name: dependabot
description: Review and merge open Dependabot PRs. Auto-merges patch/minor PRs with passing CI; flags major bumps and CI failures for manual review. Pass --dry-run to preview without merging.
argument-hint: "[--dry-run]"
allowed-tools: Bash
---

# dependabot

Review all open Dependabot PRs, enable auto-merge on safe ones, and surface anything that needs manual attention.

## Arguments

- `--dry-run` — print the summary table but do not touch any PRs.

## Steps

### 1. Fetch open Dependabot PRs

```bash
gh pr list --author "app/dependabot" --state open \
  --json number,title,url,headRefName,statusCheckRollup,mergeStateStatus \
  --limit 50
```

If the result is an empty array, tell the user "No open Dependabot PRs found." and stop.

### 2. Classify each PR

**Grouped PRs** — title matches `bump the <name> group` (no `from X to Y`): classify as **grouped**.
`dependabot.yml` restricts all groups to `minor`/`patch` only, so grouped PRs are always safe.

**Individual PRs** — parse `from X to Y` version strings from the title:
- Split each version on `.` to get components.
- Compare left to right — first differing component determines bump type:
  - Index 0 differs → **major**
  - Index 1 differs → **minor**
  - Index 2 differs → **patch**
- If either version is a bare integer (e.g. GitHub Actions `from 3 to 4`) → **major**.
- If versions cannot be parsed → **unknown** (treat as manual).

**CI status** from `statusCheckRollup`:
- All checks `SUCCESS` → **passing**
- Any `FAILURE` or `ACTION_REQUIRED` → **failing**
- Any `PENDING` / `IN_PROGRESS` / `QUEUED` → **pending**
- Empty array → **none**

**Action:**

| Bump type | CI status | Action |
|-----------|-----------|--------|
| patch, minor, or grouped | passing, pending, or none | auto-merge |
| patch, minor, or grouped | failing | manual — CI failing |
| major | any | manual — major bump |
| unknown | any | manual — unknown bump |

### 3. Display summary table

Print a table before taking any action:

```
| PR   | Title (abbreviated)                            | Bump    | CI      | Action       |
|------|------------------------------------------------|---------|---------|--------------|
| #42  | Bump spring-boot from 3.4.0 to 3.4.1          | patch   | passing | auto-merge   |
| #41  | Bump the spring group with 3 updates           | grouped | pending | auto-merge   |
| #40  | Bump nimbus-jose-jwt from 9.0 to 10.0          | major   | passing | manual       |
| #39  | Bump actions/checkout from 3 to 4              | major   | passing | manual       |
```

If `--dry-run` was passed, print the table, then print "Dry run — no changes made." and stop.

### 4. Process safe PRs

For each PR with action **auto-merge**, run these two commands in order:

**Step A** — if `mergeStateStatus` is `BEHIND`, update the branch first so CI re-runs against current main:
```bash
gh pr update-branch <number>
```

**Step B** — enable auto-merge (GitHub will merge automatically once CI passes):
```bash
gh pr merge <number> --squash --delete-branch --auto
```

Process PRs one at a time. If either command fails, note the error, skip that PR, and continue.

### 5. Print final report

```
Results
-------
Auto-merge enabled (3):
  #42  Bump spring-boot from 3.4.0 to 3.4.1
  #41  Bump the spring group with 3 updates
  #32  Bump logcaptor from 2.12.2 to 2.12.5

Needs manual review (2):
  #40  Bump nimbus-jose-jwt from 9.0 to 10.0   — major bump
         https://github.com/…/pull/40
  #39  Bump actions/checkout from 3 to 4        — major bump
         https://github.com/…/pull/39
```

For any PR that errored, include a "Errors" section with the error message.
