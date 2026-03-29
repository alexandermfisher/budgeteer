package dev.amf.budgeteer.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for integration tests that require a real PostgreSQL database.
 * 
 * <p>Uses Testcontainers to spin up a PostgreSQL 16 container. Flyway migrations
 * run automatically against this container, verifying our migrations work with
 * real PostgreSQL.</p>
 * 
 * <p>The container is shared across ALL test classes (singleton pattern) to prevent
 * container creation/destruction between test classes which causes connection timeouts.</p>
 * 
 * <h3>Usage:</h3>
 * <pre>{@code
 * class MyIntegrationIT extends AbstractPostgresIntegrationTest {
 *     @Autowired
 *     private MyRepository repository;
 *     
 *     @Test
 *     void shouldDoSomething() {
 *         // Test against real PostgreSQL
 *     }
 * }
 * }</pre>
 * 
 * <h3>When to use this vs @DataJpaTest:</h3>
 * <ul>
 *   <li>Use {@code @DataJpaTest} (H2) for simple repository query tests</li>
 *   <li>Use this class for PostgreSQL-specific features, migration tests, or full flow tests</li>
 * </ul>
 * 
 * <p><strong>Note:</strong> Requires Docker to be running.</p>
 */
@SpringBootTest
@ActiveProfiles("integration-test")
public abstract class AbstractPostgresIntegrationTest {

    /**
     * Singleton PostgreSQL container shared across ALL test classes.
     * Using PostgreSQL 16 Alpine for smaller image size.
     * 
     * Container reuse is enabled to prevent recreation between test classes.
     * The container is started once and reused for the entire test suite.
     * 
     * Protected visibility allows subclass tests to access container details if needed.
     */
    protected static final PostgreSQLContainer postgres;

    static {
        postgres = new PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("budgeteer_test")
                .withUsername("test")
                .withPassword("test")
                .withReuse(true);
        postgres.start();
    }

    /**
     * Dynamically configure Spring DataSource to use the Testcontainers PostgreSQL.
     * This replaces the need for @ServiceConnection and ensures all test classes
     * use the same container instance.
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.docker.compose.enabled", () -> "false");
    }
}
