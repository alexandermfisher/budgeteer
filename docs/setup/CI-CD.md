# CI/CD Pipeline Documentation - Budgeteer

> This document describes the CI/CD setup, GitHub Actions workflows, and branch protection rules.

---

## 📋 Overview

The Budgeteer project uses **GitHub Actions** for continuous integration and delivery. The pipeline ensures:

- ✅ All code compiles successfully
- ✅ Unit tests pass
- ✅ Integration tests pass (with real PostgreSQL via Testcontainers)
- ✅ Code style is checked (Checkstyle)
- ✅ Security vulnerabilities are scanned (CodeQL)
- ✅ Dependencies are kept up-to-date (Dependabot)
- ✅ Code reviews are required (CODEOWNERS)

---

## 🔄 Workflows

### 1. CI Pipeline (`.github/workflows/ci.yml`)

**Triggers:**
- Every push to any branch
- Pull requests to `main`

**Jobs:**

| Job | Purpose | Duration |
|-----|---------|----------|
| `build-and-test` | Compile + Unit Tests + Integration Tests | ~3-5 min |
| `code-style` | Checkstyle analysis | ~1 min |

**Steps:**
1. Checkout code
2. Set up Java 25 (Temurin)
3. Cache Maven dependencies
4. Compile project
5. Run unit tests (`*Test.java`)
6. Run integration tests (`*IT.java`)
7. Upload test results on failure

### 2. CodeQL Security Analysis (`.github/workflows/codeql.yml`)

**Triggers:**
- Push to `main`
- Pull requests to `main`
- Weekly schedule (Sunday midnight)

**Purpose:**
- Scans code for security vulnerabilities
- Identifies coding errors and bad practices
- Uses extended security queries for thorough analysis

### 3. Dependabot (`.github/dependabot.yml`)

**Schedule:** Weekly (Mondays at 9:00 AM London time)

**Manages:**
- Maven dependencies (Java/Spring)
- GitHub Actions versions
- Docker Compose images

**Features:**
- Groups related updates (Spring, testing)
- Auto-labels PRs (`dependencies`, `java`, `github-actions`, `docker`)
- Conventional commit messages (`chore(deps):`)

---

## 👥 Code Ownership (`.github/CODEOWNERS`)

All code requires approval from `@amfshr` before merging.

| Path | Owner |
|------|-------|
| `*` (default) | @amfshr |
| `/backend/` | @amfshr |
| `/frontend/` | @amfshr |
| `/.github/` | @amfshr |
| `/docs/` | @amfshr |

---

## 🛡️ Branch Protection Rules

> **Important:** These rules must be configured manually in GitHub.

### How to Configure

1. Go to **Settings** → **Branches** → **Add rule**
2. Branch name pattern: `main`
3. Enable the following:

### Required Settings

| Setting | Value | Purpose |
|---------|-------|---------|
| **Require a pull request before merging** | ✅ | No direct pushes to main |
| **Require approvals** | 1 | At least one review |
| **Dismiss stale PR approvals** | ✅ | Re-review after new commits |
| **Require review from Code Owners** | ✅ | CODEOWNERS must approve |
| **Require status checks to pass** | ✅ | CI must be green |
| **Status checks** | `build-and-test` | Require this job |
| **Require branches to be up to date** | ✅ | Must be rebased on main |
| **Block force pushes** | ✅ | Protect history |
| **Block deletions** | ✅ | Protect main branch |

### Status Checks to Require

Add these status checks after the first CI run:
- `build-and-test`
- `code-style` (optional - currently `continue-on-error`)

---

## 🧪 Running Tests Locally

```bash
# All tests
cd backend && mvn test

# Unit tests only
cd backend && mvn test -Dtest="*Test"

# Integration tests only (requires Docker)
cd backend && mvn test -Dtest="*IT"

# Checkstyle
cd backend && mvn checkstyle:check
```

---

## 📊 Code Style (Checkstyle)

Based on Google Java Style Guide with relaxed rules.

**Configuration:** `backend/config/checkstyle/checkstyle.xml`

### Key Rules

| Rule | Setting |
|------|---------|
| Max line length | 120 characters |
| Max file length | 500 lines |
| Max method length | 50 lines |
| Max parameters | 7 |
| Naming conventions | camelCase (methods), UPPER_CASE (constants) |
| No star imports | ✅ |
| No unused imports | ✅ |

### Suppressing Warnings

Use `@SuppressWarnings("checkstyle:RuleName")`:

```java
@SuppressWarnings("checkstyle:MagicNumber")
public void myMethod() {
    int value = 42; // Magic number allowed here
}
```

---

## 🔒 Security Scanning

### CodeQL

- Runs on PRs to `main` and weekly
- Scans for SQL injection, XSS, path traversal, etc.
- Results visible in **Security** → **Code scanning alerts**

### Dependabot Security Updates

- Automatically creates PRs for vulnerable dependencies
- Grouped by ecosystem (Spring, testing, etc.)
- Review and merge promptly

---

## 🚀 Deployment (Future)

The CI pipeline is designed to support future CD additions:

```yaml
# Future addition to ci.yml
deploy:
  needs: [build-and-test]
  if: github.ref == 'refs/heads/main'
  runs-on: ubuntu-latest
  steps:
    - name: Deploy to production
      # ... deployment steps
```

---

## 📈 Adding Acceptance Tests (Future)

When ready to add acceptance tests:

1. Add a new job to `ci.yml`:

```yaml
acceptance:
  name: Acceptance Tests
  needs: build-and-test
  runs-on: ubuntu-latest
  steps:
    - name: Checkout code
      uses: actions/checkout@v4
    
    - name: Set up Java 25
      uses: actions/setup-java@v4
      with:
        java-version: '25'
        distribution: 'temurin'
    
    - name: Start application
      working-directory: ./backend
      run: |
        mvn spring-boot:run &
        sleep 30  # Wait for startup
    
    - name: Run acceptance tests
      working-directory: ./backend
      run: mvn test -Dtest="*AcceptanceTest"
```

2. Add `acceptance` to required status checks in branch protection

---

## 🔧 Troubleshooting

### Test Failures in CI

1. Check the **Actions** tab for detailed logs
2. Download test artifacts (Surefire reports)
3. Look for environment differences (Docker, ports, etc.)

### Testcontainers Issues

- Ensure GitHub Actions runner has Docker
- Check container startup logs in test output
- Verify `application-integration-test.properties` settings

### Checkstyle Failures

- Run locally: `mvn checkstyle:check`
- Fix or suppress specific rules
- Consider adjusting `checkstyle.xml` if rules are too strict

### Dependabot PRs Failing

- Check if dependencies are compatible
- Run tests locally with updated dependencies
- May need to update code for breaking changes

---

## 📚 Related Documentation

- [Testing Guide](./TESTING.md)
- [Architecture Overview](./ARCHITECTURE.md)
- [Contributing Guidelines](../CONTRIBUTING.md)

---

*Last Updated: January 2026*
