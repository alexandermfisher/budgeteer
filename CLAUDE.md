# Budgeteer

Personal Monzo-integrated budgeting app. Spring Boot 3.4 / Java 25 backend, PostgreSQL 16.

> All shared AI agent context lives in `.agents/` — read the relevant file before working on any area.

## Critical Rules

- **Never push directly to `main`** — branch protection is enforced, direct pushes are blocked
- Always branch from `main`: `git checkout -b <type>/<short-description>`
- PRs require the `Build & Test` CI check to pass before merge
- Never modify existing Flyway migrations — always add a new versioned file

## Context Files

All context is in `.agents/context/` (symlinked at `.claude/context/` for tool compatibility).

| File | What's in it |
|------|-------------|
| `.agents/context/project.md` | Current status, completed phases, what's next |
| `.agents/context/architecture.md` | Tech stack, package structure, DB schema, key decisions |
| `.agents/context/conventions.md` | Commit format, branch naming, code style, Flyway rules |
| `.agents/context/commands.md` | Every build / test / run / script command |
| `.agents/context/security.md` | Auth model, encryption, what never to log |
| `.agents/context/testing.md` | Test base classes, WireMock patterns, TestDataFactory, stub file conventions |

## Shared Agent State

| File | What's in it |
|------|-------------|
| `.agents/memory.md` | Cross-session memory — read at start, update at end |
| `.agents/tasks/tasks.md` | Task board — kanban index linking to per-task subfolders |
| `.agents/tasks/<slug>/` | Per-task folder: `plan.md` + any supporting files |
| `.agents/notes/` | Planning notes, guides, reference material |

## Skills

Skill definitions live in `.agents/skills/<name>/SKILL.md` (symlinked at `.claude/skills/` for Claude Code).

| Command | What it does |
|---------|-------------|
| `/start-task` | Promote a task to In Progress, create its subfolder and `plan.md` |
| `/new-migration` | Scaffold the next Flyway migration with correct version number |
| `/check` | Run tests + checkstyle — use before raising a PR |
| `/dependabot` | Review and merge open Dependabot PRs (patch/minor auto-merged, majors flagged) |
