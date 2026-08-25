# GitHub Account Migration — budgeteer repo transfer

> **STATUS: EXECUTED 2026-08-25.** Repo transferred `alexandermfisher` → **`amfshr`** and
> accepted. Verified post-transfer: redirect active, ruleset survived, all workflows active,
> Dependabot rebasing PRs under the new owner. Local remote repointed to the
> `github.com-amfshr` SSH alias; global git email switched to
> `258678496+amfshr@users.noreply.github.com`. Remaining: reinstall GitGuardian app on
> `amfshr`; remove `alexandermfisher` collaborator entry once fully settled.

> Investigated 2026-08-25 against GitHub docs + a live inventory of this repo.
> Verdict: use GitHub's **built-in ownership transfer** — it moves everything that matters
> in one step, and this repo has unusually little to re-provision.

## TL;DR

Settings → Danger Zone → **Transfer ownership** on the old account, accept the email invite on
the new account within 1 day. Full git history, all branches/tags, issues, PRs (numbers
preserved), releases, stars, and workflow-run history move as one unit, and GitHub auto-redirects
the old URL for both web links and `git clone/fetch/push`. Post-transfer work is ~20 minutes of
checks plus one docs commit.

## Pre-flight

1. **Pick the target account** (`amfshr` is already authenticated in the `gh` CLI). The target
   must NOT already have a repo named `budgeteer` or a fork of this repo.
2. Nothing needs merging first: the 5 open Dependabot PRs (#75–#79) transfer as-is;
   `feature/domain-model-mapping` transfers with every other branch.
3. Inventory (verified 2026-08-25): **no Actions secrets, no variables, no webhooks, no deploy
   keys** — nothing to re-provision. One ruleset ("Main Branch Protection"). No Pages, no wiki
   content, no forks, no packages. Public repo, so no Actions-billing consideration.

## The transfer

- UI: old account → repo **Settings → Danger Zone → Transfer ownership** → enter new owner.
- Or CLI: `gh api repos/alexandermfisher/budgeteer/transfer -f new_owner=<target>`
- The new account gets a confirmation email — **accept within 1 day** or it expires.
- The old owner is automatically added as a collaborator on the transferred repo — remove that
  collaborator entry once verified, since the point is a clean migration.
- Reversible: the new owner can transfer back the same way.

## What carries automatically (per GitHub docs)

- Full git history and commit/contribution info, all branches, tags, Git LFS objects
- Issues, pull requests (**numbers preserved** — so every in-repo doc referencing PR #55/#67/#72/#80
  stays correct), wiki, stars, watchers, releases, workflow-run history
- Docs quote: "Webhooks, services, secrets, and deploy keys remain associated" (n/a here anyway)
- **Redirects**: all old web links and git operations on the old remote URL redirect to the new
  location — indefinitely, UNLESS a new repo is later created under the old account with the name
  `budgeteer`. Don't do that.

## Post-transfer checklist

1. **Ruleset**: confirm "Main Branch Protection" survived (Settings → Rules → Rulesets). Re-create
   from `gh api repos/<target>/budgeteer/rules/branches/main` output if not.
2. **Actions**: check the Actions tab — workflows enabled, and the CodeQL weekly cron
   (`0 0 * * 0` in codeql.yml) still scheduled. Push one commit / open one PR to see CI go green
   end-to-end under the new owner.
3. **GitHub Apps re-install** (app installations are per-account and do NOT transfer):
   - **GitGuardian** — install on the new account. Note: incident history (incl. the two
     false-positive resolutions from 2026-08-24) lives in the old account's GitGuardian workspace
     and does not migrate; the TrueLayer OpenAPI specs may get re-flagged on first scan — resolve
     as false positive again or add a `docs/api/**` path exclusion in the new workspace.
   - Any other apps visible under old account Settings → Applications → Installed GitHub Apps.
4. **Security & analysis settings** on the new repo: Dependabot alerts + security updates,
   secret scanning — these follow account-level defaults, so re-enable if off. `dependabot.yml`
   and `codeql.yml` live in-repo and keep working by themselves.
5. **CODEOWNERS** (`.github/CODEOWNERS`): every rule says `@alexandermfisher` — update to the new
   username or code-owner review simply stops matching. This is the one silent functional breakage.
6. **In-repo references** (one docs commit, no urgency — redirects cover the interim):
   - `README.md` — two badge URLs + clone URL
   - `docs/setup/CI-CD.md` — CODEOWNERS table mentions
   - `.agents/context/project.md` — "Solo project by @alexandermfisher"
   - `CHANGELOG.md` commit links can stay (redirects handle them permanently)
7. **Local clone**: `git remote set-url origin git@github.com:<target>/budgeteer.git`
8. **gh CLI**: `gh auth switch --user <target>` (both accounts already authenticated).

## Commit-email decision (settled 2026-08-25)

All existing commits are authored as `fisher.alexander.michael@gmail.com`, which the user is
**deprecating and closing**. Decision: **do NOT add that email to the new account** — complete
account separation wins. Consequences, accepted as cosmetic-only:

- Pre-migration commits show as plain-text author (no linked avatar) and earn no contribution
  squares on the new profile. Nothing functional is affected.
- The old email remains permanently visible in the public git history (it's in every commit
  object) — that's true regardless of account mapping. Scrubbing it would require a full
  history rewrite (`git filter-repo --mailmap`), which changes every SHA and breaks CHANGELOG
  commit links + merged-PR commit associations. **Rejected** — history continuity wins.
  A closed Gmail address is never re-registered, so the dead email in history is inert.

**Action at migration time — switch the going-forward git identity** (global, covers all
migrated projects):

```
git config --global user.email "<id>+<newuser>@users.noreply.github.com"
```

Use the new account's GitHub noreply address (Settings → Emails, "Keep my email addresses
private" on) so no personal email ever enters public history again.

**Because the Gmail is closing (not just deprecated):**
- Before closing it, make sure the OLD GitHub account's recovery/notification email situation is
  sorted (transfer-acceptance emails go to the NEW account's email, but the old account still
  needs to be operable to initiate transfers).
- Don't lean on GitHub's URL redirects long-term: if the old account is later deleted and someone
  claims the freed username, redirects break. Update remotes and external links promptly.

## What is NOT affected

- The local working copy and its path (`~/development/budgeteer`) — nothing changes on disk.
- Claude Code session memory (keyed by local path).
- Monzo OAuth app config / redirect URIs — external to GitHub entirely.
- `.env` secrets — never in GitHub to begin with.
