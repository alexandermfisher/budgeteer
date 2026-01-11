package dev.amf.budgeteer.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests that require a real PostgreSQL database.
 * 
 * <p>Uses Testcontainers to spin up a PostgreSQL 16 container. Flyway migrations
 * run automatically against this container, verifying our migrations work with
 * real PostgreSQL.</p>
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
@Testcontainers
@ActiveProfiles("integration-test")
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.docker.compose.enabled=false"
})
public abstract class AbstractPostgresIntegrationTest {

    /**
     * PostgreSQL container - shared across all tests in the class.
     * Using PostgreSQL 16 Alpine for smaller image size.
     * 
     * @ServiceConnection tells Spring Boot to automatically configure
     * DataSource, Flyway, etc. to use this container's connection.
     */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("budgeteer_test")
            .withUsername("test")
            .withPassword("test");
}
