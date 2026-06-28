# Monzo-Client Jar — Auto-Configuration, Test Hardening & Token-Injection Review

> **Status:** Queue (ready to build — design resolved below)
> **Priority:** P2 | **Estimate:** ~1–1.5d | **Branch:** `refactor/monzo-client-autoconfig`

## Goal

Turn `monzo-client` from a jar that only works because it borrows the app's component-scan
(`dev.amfshr.budgeteer.*`) into a **properly self-contained, decoupled, well-tested Spring Boot
library** — the reusable template that `truelayer-client` will copy.

The jar must **come to life purely from being on the classpath** — no component-scan wiring from
the consumer app. The consumer app **owns and configures the `RestClient`**; the jar uses whatever
the app hands it.

---

## 1. Auto-Configuration (decouple the jar)

### How Spring auto-config works

When `monzo-client` is on the classpath, Spring Boot reads
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` and processes
`MonzoAutoConfiguration`. No `@ComponentScan` from the consumer app needed.

### Consumer-configurable `RestClient` — the core design

The consumer app (or a test) **defines a `RestClient` bean named `monzoRestClient`** and the
auto-config uses it to construct `MonzoBankClient`. The jar never creates its own `RestClient`
with hardcoded defaults — the consumer is always in control of SSL config, timeouts, interceptors,
connection pool, etc.

**Auto-config wire-up:**

```java
@AutoConfiguration
@EnableConfigurationProperties(MonzoProperties.class)
@ConditionalOnClass(MonzoBankClient.class)
public class MonzoAutoConfiguration {

    // Creates MonzoBankClient only if the consumer hasn't defined their own
    @Bean
    @ConditionalOnMissingBean
    public MonzoBankClient monzoBankClient(
            @Qualifier("monzoRestClient") RestClient restClient,
            MonzoProperties props
    ) {
        return new MonzoBankClient(restClient, props);
    }
}
```

**Consumer app (budgeteer-api) defines the `RestClient`:**

```java
@Configuration
public class MonzoClientConfig {

    @Bean
    public RestClient monzoRestClient(MonzoProperties props, RestClient.Builder builder) {
        return builder
            .baseUrl(props.apiBaseUrl())
            .defaultHeader("Content-Type", "application/x-www-form-urlencoded")
            // add timeouts, logging interceptors, etc. here
            .build();
    }
}
```

The auto-config injects `@Qualifier("monzoRestClient")` — so it picks up exactly what the
consumer provided. If the consumer also wants to override `MonzoBankClient` itself, they just
define their own `@Bean MonzoBankClient` and the `@ConditionalOnMissingBean` on the auto-config
skips it.

### What changes in the jar

- **Delete** `MonzoClientConfig.java` — the consumer owns `RestClient` creation.
- **Delete** `@Component` from `MonzoBankClient` — it becomes a plain class wired by auto-config.
- **Add** `MonzoAutoConfiguration.java` with the `@AutoConfiguration` + `@EnableConfigurationProperties`.
- **Add** `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  containing `dev.amfshr.budgeteer.client.monzo.MonzoAutoConfiguration`.
- `MonzoProperties` keeps `@ConfigurationProperties(prefix = "monzo")` — `@EnableConfigurationProperties`
  in the auto-config registers it without needing the consumer's `@ConfigurationPropertiesScan`.
- **Remove** the wide `@ConfigurationPropertiesScan("dev.amfshr.budgeteer")` from `BudgeteerApplication`
  — replace with `@ConfigurationPropertiesScan("dev.amfshr.budgeteer")` scoped to app-only properties,
  or let the auto-config handle `MonzoProperties` registration entirely (preferred).

### Result

The jar is inert until the consumer provides `application.yml` properties + a `monzoRestClient` bean.
When both are present, `MonzoBankClient` wires up automatically. No scanning of `client.monzo` from
the app.

---

## 2. Test Hardening

Current coverage is thin — one WireMock test (`MonzoBankClientTest`). Target:

### Mirror the package structure

```
src/test/java/dev/amfshr/budgeteer/client/monzo/
  MonzoBankClientTest.java          ← existing, expand
  MonzoAutoConfigurationTest.java   ← new: verify auto-config fires / is skipped correctly
  MonzoMapperTest.java              ← new: unit-test mapping edge cases
```

### Integration test — auto-config boots and works

Use `@SpringBootTest` with a minimal `@SpringBootApplication` stub inside the test tree
(pattern: inner `@SpringBootApplication` class in the test) + WireMock server. Verifies:
- `MonzoBankClient` bean is present.
- Real HTTP call to WireMock returns a correctly mapped `BankAccount` / `BankTransaction`.

### Auto-config conditional test

Use `ApplicationContextRunner` (no server start) to verify:
- With `monzo.*` properties + `monzoRestClient` bean → `MonzoBankClient` is registered.
- With `monzoRestClient` bean missing → fail fast with clear error (or no client — define behaviour).
- With consumer-provided `MonzoBankClient` bean → auto-config's bean is skipped (`@ConditionalOnMissingBean`).

### Fixtures

Move / expand JSON fixtures to `src/test/resources/wiremock/` inside `monzo-client`
following the `TestMonzoData` fixture-driven pattern used in `budgeteer-api`. Give
each scenario its own subdirectory (`oauth/`, `accounts/`, `transactions/`, `identity/`).

---

## 3. Token Injection — Confirmed: Parameter Passing (keep as-is)

`BankClient` methods take `accessToken` as a plain `String` parameter. This is correct and
intentional — one `MonzoBankClient` bean serves all users; the per-call token varies.

Token storage, decryption (AES-256-GCM), and refresh all stay in `budgeteer-api`
(`MonzoConnectionService`, `MonzoTokenRefreshService`). The jar receives plaintext only.

An interceptor-based approach would require per-request scoping (thread-local or context) to
pass the user's token to the interceptor — more complexity with no benefit vs. param-passing.
**No change needed here.**

---

## 4. Shared OAuth → `common`? (Resolved: no impl in common)

The contract is already in `common` (`BankClient.buildAuthorizationUrl / exchangeCode / refreshTokens`).
A shared *impl* in `common` would drag `spring-web`/HTTP/Jackson into a dependency-light jar — wrong.
Each client jar implements its own OAuth (Monzo vs TrueLayer differ: endpoints, scopes, PKCE).
OAuth *state* orchestration (CSRF, DB-backed `OAuthState`, `User` link) stays in `budgeteer-api`.

---

## 5. Multi-bank wiring (context)

Once `monzo-client` auto-configures correctly, `truelayer-client` copies the same pattern.
With both jars on the classpath, there will be two `BankClient` beans — the app selects by
provider via `Map<String, BankClient>` keyed by provider name, or `@Qualifier`. The auto-config
will need a provider-name qualifier (e.g. `@Bean("monzo") MonzoBankClient`).

---

## Implementation Checklist

- [ ] Delete `MonzoClientConfig.java`
- [ ] Remove `@Component` from `MonzoBankClient`
- [ ] Write `MonzoAutoConfiguration.java`
- [ ] Add `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- [ ] Add `monzoRestClient` `@Bean` to `budgeteer-api` config (consumer owns it)
- [ ] Narrow `@ConfigurationPropertiesScan` in `BudgeteerApplication` (remove `client.monzo` scan)
- [ ] Add `MonzoAutoConfigurationTest` (ApplicationContextRunner)
- [ ] Add integration test with WireMock + minimal Spring context
- [ ] Add `MonzoMapperTest`
- [ ] Expand fixture files under `monzo-client/src/test/resources/wiremock/`
- [ ] Verify all existing tests still pass (`./mvnw test`)
