# Budgeteer

Personal Monzo-integrated budgeting app. Spring Boot 3.4 / Java 25 backend, PostgreSQL 16.

> Detailed context lives in `.claude/context/` — read the relevant file before working on any area.

## Critical Rules

- **Never push directly to `main`** — branch protection is enforced, direct pushes are blocked
- Always branch from `main`: `git checkout -b <type>/<short-description>`
- PRs require the `Build & Test` CI check to pass before merge
- Never modify existing Flyway migrations — always add a new versioned file

## Context Files

| File | What's in it |
|------|-------------|
| `.claude/context/project.md` | Current status, completed phases, what's next |
| `.claude/context/architecture.md` | Tech stack, package structure, DB schema, key decisions |
| `.claude/context/conventions.md` | Commit format, branch naming, code style, Flyway rules |
| `.claude/context/commands.md` | Every build / test / run / script command |
| `.claude/context/security.md` | Auth model, encryption, what never to log |

## Skills

Skill definitions live in `.claude/skills/<name>/SKILL.md`.

| Command | What it does |
|---------|-------------|
| `/new-migration` | Scaffold the next Flyway migration with correct version number |
| `/check` | Run tests + checkstyle — use before raising a PR |
