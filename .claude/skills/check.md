# check

Run the full pre-PR quality gate: unit tests, integration tests, and checkstyle.

## Steps

1. Run checkstyle first (fast fail):
   ```
   cd backend && mvn checkstyle:check
   ```
   If it fails, report the violations and stop — fix style issues before running tests.

2. Run unit tests:
   ```
   cd backend && mvn test -DexcludedGroups=integration
   ```
   Report pass/fail and any failures with the test name and error message.

3. Run integration tests (requires Docker running):
   ```
   cd backend && mvn test -Dgroups=integration
   ```
   If Docker is not running, warn the user and skip this step.

4. Summarise results:
   - Checkstyle: pass / fail (N violations)
   - Unit tests: N passed, N failed
   - Integration tests: N passed, N failed / skipped (Docker not running)
   - Overall: READY TO PR or FIXES NEEDED
