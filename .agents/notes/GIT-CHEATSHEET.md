# Git Conventions Cheatsheet 📋

> Quick reference for solo development. Full details in `CONTRIBUTING.md`

---

## 🏷️ Commit Message Format

```
<type>(<scope>): <short description>

[optional body]
```

### Types

| Type | When to use | Example |
|------|-------------|---------|
| `feat` | New feature | `feat(auth): add token persistence` |
| `fix` | Bug fix | `fix(api): handle null balance` |
| `refactor` | Code improvement (no behavior change) | `refactor: extract MonzoClient` |
| `docs` | Documentation only | `docs: update setup guide` |
| `test` | Adding/updating tests | `test(auth): add TokenService tests` |
| `chore` | Maintenance (deps, config) | `chore: upgrade Spring Boot` |
| `style` | Formatting, whitespace | `style: fix indentation` |

### Scopes (Optional)

`auth`, `monzo`, `db`, `api`, `config`, `sync`

### Examples

```bash
# Features
feat(auth): add token persistence to database
feat(monzo): implement transaction sync

# Fixes
fix(auth): handle expired refresh tokens
fix: correct null pointer in balance check

# Refactoring
refactor(monzo): extract API client class
refactor: rename services for clarity

# Maintenance
chore: upgrade dependencies
chore(deps): update PostgreSQL driver

# Documentation
docs: add deployment guide
docs(api): document new endpoints
```

---

## 🌿 Branch Strategy (Solo Dev)

### The Rules

1. **`main` is stable** - should always work
2. **Feature branches for bigger work** - isolate risky changes
3. **Direct commits to `main` are OK** for small changes (you're the only dev!)
4. **Tag releases** when deploying

### Branch Naming

```
<type>/<short-description>

feature/token-persistence
fix/null-balance
refactor/monzo-client
docs/deployment-guide
chore/spring-upgrade
```

### Workflow Options

**Option A: Small changes - commit directly to main**
```bash
# You're on main, making a quick fix
git add .
git commit -m "fix: handle empty response from Monzo"
```

**Option B: Bigger feature - use a branch**
```bash
# Create feature branch
git checkout -b feature/token-persistence

# Do work, commit often
git add .
git commit -m "feat(auth): create TokenRepository"
git commit -m "feat(auth): add encryption service"
git commit -m "test(auth): add TokenService tests"

# Merge back to main
git checkout main
git merge feature/token-persistence

# Delete the branch
git branch -d feature/token-persistence
```

**Option C: Experimental - branch, might abandon**
```bash
git checkout -b experiment/crazy-idea
# Try stuff...
# If it works: merge to main
# If it fails: git checkout main && git branch -D experiment/crazy-idea
```

---

## 🏷️ Tagging Releases

```bash
# Tag a version
git tag v0.1.0
git tag v0.2.0 -m "Add transaction sync"

# List tags
git tag

# Push tags (when you have a remote)
git push origin v0.1.0
git push --tags  # Push all tags
```

---

## 📝 Daily Workflow

### Starting Work

```bash
# Check where you are
git status
git log --oneline -5

# Option 1: Work on main (small stuff)
# Just start coding

# Option 2: Create branch (bigger feature)
git checkout -b feature/whatever
```

### During Work

```bash
# Stage specific files
git add src/main/java/...

# Stage everything
git add .

# Commit with conventional message
git commit -m "feat(auth): add token encryption"

# Check status often
git status
git log --oneline -3
```

### Finishing Work

```bash
# If on a feature branch, merge to main
git checkout main
git merge feature/whatever
git branch -d feature/whatever

# If ready for release, tag it
git tag v0.1.0
```

---

## 🚫 The "Rules" (Self-Imposed)

| Rule | Reason |
|------|--------|
| Use conventional commits | Clean git history |
| Keep commits atomic | Easy to understand/revert |
| Don't commit secrets | Security (even without remote) |
| Tag releases | Know what's deployed |
| Branch for risky stuff | Easy to abandon if it fails |

### When to Branch vs Commit to Main

| Situation | Approach |
|-----------|----------|
| Quick bug fix | Commit to `main` |
| Small refactor | Commit to `main` |
| New feature (1-2 hours) | Either works |
| Big feature (days) | Feature branch |
| Experimental/risky | Feature branch |
| Not sure if it'll work | Feature branch |

---

## 🎯 Quick Commands

```bash
# Status
git status
git log --oneline -10

# Branching
git checkout -b feature/name    # Create & switch
git checkout main               # Switch to main
git branch                      # List branches
git branch -d feature/name      # Delete branch

# Committing
git add .
git commit -m "type(scope): message"

# Merging
git checkout main
git merge feature/name

# Tagging
git tag v1.0.0
git tag                         # List tags

# Undo last commit (keep changes)
git reset --soft HEAD~1

# Undo changes to a file
git checkout -- path/to/file
```

---

## 🔗 Reference

Full conventions: [`CONTRIBUTING.md`](../CONTRIBUTING.md)

---

*You're the only developer - these rules serve YOU, not bureaucracy!*
