package dev.amfshr.budgeteer.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;

/**
 * Base class for integration tests that require both a real PostgreSQL database and a
 * WireMock server to stub the Monzo API.
 *
 * <p>Extends {@link AbstractPostgresIntegrationTest} for the Postgres container, and
 * adds a singleton WireMock server started in a static initialiser so it is available
 * before Spring's {@code @DynamicPropertySource} callback runs.</p>
 *
 * <p>WireMock stubs are loaded from {@code src/test/resources/wiremock/mappings/} at
 * server start. Each test gets a clean stub registry: {@code wm.resetAll()} runs in
 * {@code @BeforeEach} so stubs and the request journal are cleared between tests.
 * Add per-test stubs in the test method or a test-class {@code @BeforeEach}.</p>
 *
 * <h3>Usage:</h3>
 * <pre>{@code
 * @DisplayName("My Monzo Tests")
 * class MyMonzoIT extends AbstractMonzoWireMockIT {
 *
 *     @Test
 *     void shouldCallMonzo() {
 *         wm.stubFor(get(urlPathEqualTo("/accounts"))
 *                 .willReturn(okJson("{\"accounts\":[]}")));
 *         // ...
 *     }
 * }
 * }</pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractMonzoWireMockIT extends AbstractPostgresIntegrationTest {

    /**
     * Singleton WireMock server shared across all subclass test runs.
     * {@code wm.resetAll()} in {@code @BeforeEach} clears all stubs before each test,
     * so the file stubs loaded at server start are not automatically served. Use
     * {@link #loadStubFromFile} to load a specific mapping file by classpath path when
     * you need its canned response in a test.
     */
    protected static final WireMockServer wm;

    static {
        wm = new WireMockServer(WireMockConfiguration.options()
                .dynamicPort()
                .usingFilesUnderClasspath("wiremock"));
        wm.start();
        configureFor("localhost", wm.port());
    }

    @DynamicPropertySource
    static void configureMonzoUrls(DynamicPropertyRegistry registry) {
        String base = "http://localhost:" + wm.port();
        registry.add("monzo.token-url", () -> base + "/oauth2/token");
        registry.add("monzo.api-base-url", () -> base);
    }

    @BeforeEach
    void resetWireMock() {
        wm.resetAll();
    }

    /**
     * Loads a WireMock stub mapping from a classpath JSON file and registers it with
     * the running server. Call this after {@code resetAll()} has cleared the defaults.
     *
     * <pre>{@code
     * loadStubFromFile("wiremock/mappings/monzo/accounts/accounts-list.json");
     * loadStubFromFile("wiremock/mappings/monzo/transactions/transactions-list.json");
     * }</pre>
     *
     * @param classpathPath path relative to the classpath root (no leading slash)
     */
    protected static void loadStubFromFile(String classpathPath) {
        try (InputStream is = AbstractMonzoWireMockIT.class.getClassLoader()
                .getResourceAsStream(classpathPath)) {
            if (is == null) {
                throw new IllegalArgumentException("Stub file not found on classpath: " + classpathPath);
            }
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            wm.addStubMapping(StubMapping.buildFrom(json));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load stub from " + classpathPath, e);
        }
    }
}
