# API Endpoint Versioning (`/api/v1`)

> **Priority:** 🟡 P2 | **Estimate:** 0.5–1 day | **Status:** Spec ready | **Branch:** `feat/api-v1-versioning`

## Goal

Introduce URL-path versioning on the public API — `/api/auth/...` → `/api/v1/auth/...`,
`/api/monzo/...` → `/api/v1/monzo/...` — so a future breaking change can ship as `/api/v2`
alongside `/api/v1` without disrupting existing clients. Cheap to do now: no frontend, no external
consumers. Locks in the convention before the surface grows.

## Why now

There are no clients to break and the OAuth redirect URI has to change for prod anyway (ngrok is
dropped), so re-pointing the Monzo callback costs nothing extra. Per the board this is sequenced
**after** the multi-module restructure (#6) + source migration (#8) so the controllers are in their
final module first — but this spec is **layout-agnostic** (it edits `@RequestMapping` strings,
SecurityConfig matchers, config, and tests — never package structure), so it can be implemented
before or after that work without change.

## Acceptance Criteria

- [ ] All **product** endpoints respond under `/api/v1/...` (auth + monzo, incl. the OAuth callback).
- [ ] The **old** paths (`/api/auth/...`, `/api/monzo/...`) return **404** — the cut-over is clean, not additive.
- [ ] `/actuator/**`, `/api/dev/**`, and `/api/health/**` are **unchanged**.
- [ ] Public endpoints (`/api/v1/auth/{login,verify,refresh,logout}`, `/api/v1/monzo/callback`) remain `permitAll`; everything else under `/api/**` stays authenticated.
- [ ] Monzo OAuth + transaction-sync flow works end-to-end on the new paths (full `mvn verify` green).
- [ ] `monzo.redirect-uri` (config + `.env.example` + integration-test props) points at `…/api/v1/monzo/callback`; the manual Monzo Developer Portal update is documented as a deploy step.

## Out of Scope

- **`/v2` / multi-version content negotiation** — not needed until a real breaking change lands. When it does, reach for Spring's first-class versioning (see Decisions Log #1), not more URL juggling.
- **Deleting the deprecated `HealthController`** — it's dead weight (duplicates `/actuator/health`) but removing an endpoint is a separate concern; do it as its own tiny PR.
- **Versioning `/api/dev/**` and `/api/health/**`** — operational/throwaway surface, not a product contract.
- **OpenAPI/Swagger** — none in the project; not introduced here.

## Codebase Findings

Verified against the repo (Spring Boot **4.0.6** / Spring Framework 7, Java 25, base package
`dev.amfshr.budgeteer`):

- **Only 2 product controllers**, both with **class-level** `@RequestMapping` — so the prefix is 2 edits and every method path cascades:
  - `api/auth/AuthController.java` → `/api/auth` (login, verify, refresh, logout, me)
  - `api/monzo/MonzoController.java` → `/api/monzo` (connect ×2, callback, connections ×3, status, sync/progress)
- **SecurityConfig** (`config/SecurityConfig.java`, `authorizeHttpRequests` block) lists **5 explicit `permitAll()` matchers** that must move to `/api/v1`. The trailing `/api/**` → `authenticated()` catch-all and the CORS `registerCorsConfiguration("/api/**", …)` **already cover `/api/v1/**`** — leave both as-is.
- **JweAuthenticationFilter** has **no** hardcoded paths — SecurityConfig is the single source of truth for which routes are public.
- **`MonzoOAuthService`** builds the auth URL from the `monzo.redirect-uri` **config value** — there is **no hardcoded callback path in service code**. So no business-logic change; only config + the controller mapping.
- **Pre-existing bug:** `monzo.redirect-uri` default (`application.properties`) and `.env.example` both say `…/auth/callback`, which doesn't even match today's `/api/monzo/callback`. Only `application-integration-test.properties` is correct. We fix all three to the v1 path.
- **Nothing operational depends on `/api/health`** — no Dockerfile, no compose healthcheck, no CI/deploy script. Referenced only in docs and the request-logging exclusion → safe to leave untouched.
- No `server.servlet.context-path`, no existing versioning, no springdoc.
- **Downstream churn:** ~35 hardcoded test paths, 2 Postman collections, 2 doc diagrams.

## Decisions Log

| # | Decision | Rationale | Rejected alternative |
|---|----------|-----------|----------------------|
| 1 | **Per-controller** explicit `@RequestMapping("/api/v1/...")` | Version segment is part of the **contract**, so it belongs in **code**, not config. 2 class-level edits; greppable; survives the module move. | **`PathMatchConfigurer.addPathPrefix`** — adds a marker annotation + config and *still* requires editing each controller (to drop `/api`), and makes full paths un-greppable; pays off only with many controllers. **`server.servlet.context-path`** — pure config but all-or-nothing, can't run v1+v2 side by side (defeats versioning). **Spring-native `@RequestMapping(version=…)`** (available on Boot 4) — the "proper" tool for true content negotiation; overkill for stamping a URL prefix with no v2 yet. |
| 2 | **Version all product endpoints incl. the OAuth callback** → `/api/v1/monzo/callback` | Consistency — what it should have been from the start; the redirect URI must change for prod regardless. | Leaving the callback at `/api/monzo/callback` — inconsistent, and still needs a portal change anyway. |
| 3 | **Leave `/api/health/**` unversioned** | Deprecated, duplicates `/actuator/health`, nothing operational depends on it. Zero churn: matcher, tests, and log-exclusion all stay valid. | Version it (churn for a deprecated dupe) / delete it now (separate cleanup concern → Out of Scope). |
| 4 | **Fix `monzo.redirect-uri` fully** (default + `.env.example` + integration-test props) | The default is already wrong (`/auth/callback`); fix the latent bug in the same sweep. Only the **path** changes — the **host** stays in config (it's the genuinely env-varying part). | Minimal touch (integration-test props only) — leaves the latent bug for future-you to trip over. |
| 5 | **Clean cut-over (old paths 404), not additive** | No clients exist; running both old+new just doubles the surface and the matcher list for no benefit. | Keep old paths as aliases — pointless maintenance burden. |

## API Contract

Path-only change — request/response DTOs, auth, and status codes are **unchanged**.

| Area | Before | After |
|------|--------|-------|
| Auth | `/api/auth/{login,verify,refresh,logout,me}` | `/api/v1/auth/{…}` |
| Monzo | `/api/monzo/{connect,callback,connections,connections/{id},status,sync/progress}` | `/api/v1/monzo/{…}` |
| Health | `/api/health/**` | **unchanged** (deprecated; use `/actuator/health`) |
| Dev | `/api/dev/**` | **unchanged** |
| Actuator | `/actuator/**` | **unchanged** |

### SecurityConfig change (exact)

In `config/SecurityConfig.java` `authorizeHttpRequests(...)`, update the 5 product matchers in place
(order preserved — specific `permitAll` rules stay *before* the `/api/**` catch-all):

```diff
- .requestMatchers("/api/auth/login").permitAll()
- .requestMatchers("/api/auth/verify").permitAll()
- .requestMatchers("/api/auth/refresh").permitAll()
- .requestMatchers("/api/auth/logout").permitAll()
- .requestMatchers("/api/monzo/callback").permitAll()
+ .requestMatchers("/api/v1/auth/login").permitAll()
+ .requestMatchers("/api/v1/auth/verify").permitAll()
+ .requestMatchers("/api/v1/auth/refresh").permitAll()
+ .requestMatchers("/api/v1/auth/logout").permitAll()
+ .requestMatchers("/api/v1/monzo/callback").permitAll()
  // UNCHANGED: /api/health/** , /actuator/** , /api/dev/** , /error ,
  //            /api/** authenticated (still catches /api/v1/**) , CORS /api/**
```

> ⚠️ If any product `permitAll` matcher is missed, that endpoint falls through to `/api/**` →
> `authenticated()` and breaks (e.g. login would demand a session). Update all five.

### Controller change (exact)

```diff
// api/auth/AuthController.java
- @RequestMapping("/api/auth")
+ @RequestMapping("/api/v1/auth")

// api/monzo/MonzoController.java
- @RequestMapping("/api/monzo")
+ @RequestMapping("/api/v1/monzo")
```

## Service Logic

No change. `MonzoOAuthService` reads `monzo.redirect-uri` from config to build the OAuth auth URL;
moving the callback path is purely a config + controller-mapping change. Do **not** introduce a
hardcoded path in the service.

## Configuration

Only the **path** moves to `/api/v1`; the **host** stays env-driven via `MONZO_REDIRECT_URI`.

```properties
# application.properties  (fix the already-wrong default path)
-monzo.redirect-uri=${MONZO_REDIRECT_URI:http://localhost:8080/auth/callback}
+monzo.redirect-uri=${MONZO_REDIRECT_URI:http://localhost:8080/api/v1/monzo/callback}

# application-integration-test.properties
-monzo.redirect-uri=http://localhost:8080/api/monzo/callback
+monzo.redirect-uri=http://localhost:8080/api/v1/monzo/callback
```

```diff
# .env.example
-MONZO_REDIRECT_URI=https://your-ngrok-url.ngrok-free.dev/auth/callback
+MONZO_REDIRECT_URI=https://<your-prod-domain>/api/v1/monzo/callback
```

**Manual deploy step (cannot be done in code):** update the redirect URI in the **Monzo Developer
Portal** to `https://<prod-domain>/api/v1/monzo/callback`, and set the prod `MONZO_REDIRECT_URI` to
match. Until both are done, the prod callback returns a Monzo redirect-URI-mismatch error.

## Edge Cases & Failure Modes

| Case | Handling |
|------|----------|
| Product `permitAll` matcher missed | Endpoint breaks (caught by `/api/**` authenticated). Mitigation: AC requires all 5 updated + an `oldPath_returns404` / `publicPath_permitAll` test. |
| Redirect URI mismatch (3 code spots + portal must agree) | Keep controller path, `monzo.redirect-uri`, and the portal in lockstep. Portal is a documented manual deploy step. |
| Someone "helpfully" changes CORS / catch-all | Don't. `/api/**` still matches `/api/v1/**`. Leave both untouched. |
| Health/dev/actuator accidentally versioned | Out of scope — they must stay exactly as-is (matcher, tests, log-exclusion). |
| Hardcoded host in redirect URI | Only the path changes; the host stays in `MONZO_REDIRECT_URI` (env-varying). |

## Test Strategy

**Unit:**
- `AuthControllerTest` — repoint all paths to `/api/v1/auth/...`. **Add** `oldPath_returns404`:
  `GET /api/auth/me` (or `POST /api/auth/login`) → 404, proving the cut-over.
- `MonzoControllerTest` — repoint all paths to `/api/v1/monzo/...`.
- `RequestLoggingFilterTest` — paths are *incidental* to the filter; repoint the product ones
  (`/api/auth/*`) to v1 for realism. Leave its health/dev paths as-is.

**Integration:**
- `MonzoOAuthFlowIT` — repoint the callback to `/api/v1/monzo/callback`; assert the built auth URL
  contains the v1 redirect URI; full OAuth flow stays green.
- `DevSyncTriggerIT` and other dev ITs — **unchanged** (dev paths stay).
- Grep the whole `src/test` tree for `"/api/auth` and `"/api/monzo` to catch any stragglers.

No new test base classes or `TestDataFactory` helpers needed.

## New Files

None — this is a pure modification.

## Modified Files

| Path | Change |
|------|--------|
| `backend/src/main/java/dev/amfshr/budgeteer/api/auth/AuthController.java` | Class `@RequestMapping` → `/api/v1/auth` |
| `backend/src/main/java/dev/amfshr/budgeteer/api/monzo/MonzoController.java` | Class `@RequestMapping` → `/api/v1/monzo` |
| `backend/src/main/java/dev/amfshr/budgeteer/config/SecurityConfig.java` | 5 `permitAll` matchers → `/api/v1/...` |
| `backend/src/main/resources/application.properties` | `monzo.redirect-uri` default → `…/api/v1/monzo/callback` |
| `backend/src/main/resources/application-integration-test.properties` | `monzo.redirect-uri` → `…/api/v1/monzo/callback` |
| `.env.example` | `MONZO_REDIRECT_URI` example → `…/api/v1/monzo/callback` |
| `backend/src/test/java/dev/amfshr/budgeteer/api/auth/AuthControllerTest.java` | Paths → v1; add `oldPath_returns404` |
| `backend/src/test/java/dev/amfshr/budgeteer/api/monzo/MonzoControllerTest.java` | Paths → v1 |
| `backend/src/test/java/dev/amfshr/budgeteer/integration/MonzoOAuthFlowIT.java` | Callback → `/api/v1/monzo/callback`; auth-URL assertion |
| `backend/src/test/java/dev/amfshr/budgeteer/.../RequestLoggingFilterTest.java` | Product paths → v1 (incidental) |
| `scripts/postman/budgeteer-auth.postman_collection.json` | auth + monzo paths → v1 (leave dev/health) |
| `scripts/postman/budgeteer-transaction-sync.postman_collection.json` | monzo paths → v1 (leave dev) |
| `docs/diagrams/user-authentication-flow.md` | `/api/auth/...` → v1 |
| `docs/diagrams/monzo-oauth-flow.md` | `/api/monzo/...` → v1 |

> Leave `docs/features/LOGGING.md` and the `docs/testing/*` health snippets unchanged — they
> reference `/api/health`, which stays.

## Implementation Order

1. `AuthController` + `MonzoController` class mappings → `/api/v1/...` (compile).
2. `SecurityConfig` — 5 `permitAll` matchers → `/api/v1/...`.
3. Config: `application.properties` + `application-integration-test.properties` + `.env.example` redirect-uri → v1 path.
4. Unit tests: repoint `AuthControllerTest` + `MonzoControllerTest`; add `oldPath_returns404`.
5. Integration: repoint `MonzoOAuthFlowIT`; `grep -rn '"/api/auth\|"/api/monzo' src/test` and fix stragglers; repoint `RequestLoggingFilterTest` product paths.
6. Postman collections + doc diagrams.
7. `/check` (or `mvn verify`) green; manual OAuth + sync smoke on the new paths.
8. **Deploy checklist:** update Monzo Developer Portal redirect URI + prod `MONZO_REDIRECT_URI`.

## Key Files to Read Before Implementing

| File | Why |
|------|-----|
| `config/SecurityConfig.java` | Matcher list + order; the `/api/**` catch-all and CORS that must stay |
| `api/auth/AuthController.java`, `api/monzo/MonzoController.java` | Class-level mappings to change |
| `service/monzo/MonzoOAuthService.java` (or wherever the auth URL is built) | Confirm redirect URI comes from config, not a hardcoded path |
| `application.properties` + `application-integration-test.properties` | `monzo.redirect-uri` keys |
| `integration/MonzoOAuthFlowIT.java` | Callback test + auth-URL assertion pattern |

## Open Questions / Assumptions

- **Assumption:** clean cut-over (old paths 404). No backward-compat aliases. Revisit only if an external client appears before this ships.
- **Assumption:** implemented in the controllers' final location regardless of whether the multi-module move (#6/#8) has happened — the spec edits annotations/config, so it holds either way.
- **External, manual:** the Monzo Developer Portal redirect-URI change is outside the codebase and must be done at deploy time, or the prod callback breaks.

---

## Implementer Kickoff Prompt

> Copy-paste this to the implementing model/tool.

You are implementing **API Endpoint Versioning (`/api/v1`)** in the Budgeteer repo (Spring Boot 4.0.6 / Java 25 / PostgreSQL 16, base package `dev.amfshr.budgeteer`).

**Before writing any code, read:** `.agents/context/architecture.md`, `.agents/context/conventions.md`, `.agents/context/testing.md`, this spec, and every file in *Key Files to Read Before Implementing*.

**Then:** branch from `main` (`git checkout -b feat/api-v1-versioning`) and implement strictly in the order in *Implementation Order*. This is a **path-only** change — do not alter any DTO, status code, auth rule, or business logic. Follow the *Codebase ground rules*: constructor injection, thin controllers, checkstyle limits, never log secrets.

Make **exactly** the edits in *Modified Files*; create no new files. Specifically: move the 2 product controllers' class-level `@RequestMapping` to `/api/v1/...`, update the **5** `permitAll` matchers in `SecurityConfig` (leave the `/api/**` catch-all and CORS `/api/**` untouched), fix `monzo.redirect-uri` in both properties files + `.env.example` (path only — keep the host env-driven), and repoint all `/api/auth` + `/api/monzo` references in tests, Postman, and the 2 doc diagrams. **Do not touch** `/api/health/**`, `/api/dev/**`, `/actuator/**`, or the health/dev doc snippets.

**Do not** redesign, add scope (no `/v2`, no Spring-native `@RequestMapping(version=…)`, no deleting HealthController), or deviate from this spec. If something is genuinely underspecified, stop and ask.

**Definition of Done:** every *Acceptance Criteria* box ticked (product endpoints on `/api/v1`, old paths 404, public endpoints still `permitAll`, OAuth+sync flow green), all tests in *Test Strategy* updated/passing incl. `oldPath_returns404`, and `/check` (checkstyle + unit + integration) green before opening the PR. Flag in the PR description that the **Monzo Developer Portal redirect URI** must be updated manually at deploy.
