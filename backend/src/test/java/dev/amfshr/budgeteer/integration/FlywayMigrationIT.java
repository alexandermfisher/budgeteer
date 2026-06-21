package dev.amfshr.budgeteer.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests that verify Flyway migrations run correctly against PostgreSQL.
 * 
 * <p>These tests ensure:</p>
 * <ul>
 *   <li>All migrations execute without errors</li>
 *   <li>Expected tables are created</li>
 *   <li>Schema matches what we expect</li>
 * </ul>
 * 
 * <p>Uses real PostgreSQL via Testcontainers.</p>
 */
@DisplayName("Flyway Migration Integration Tests")
class FlywayMigrationIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Database connection should work and show database info")
    void databaseConnectionShouldWork() {
        // Check we can connect and what database we're connected to
        String currentDb = jdbcTemplate.queryForObject("SELECT current_database()", String.class);
        String currentSchema = jdbcTemplate.queryForObject("SELECT current_schema()", String.class);
        
        System.out.println("Connected to database: " + currentDb);
        System.out.println("Current schema: " + currentSchema);
        System.out.println("Container running: " + postgres.isRunning());
        System.out.println("Container JDBC URL: " + postgres.getJdbcUrl());
        
        // List all tables in public schema
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' ORDER BY table_name",
                String.class
        );

        System.out.println("Tables found in database: " + tables);

        assertThat(tables)
                .as("Expected tables should exist - Flyway should have created them")
                .contains("flyway_schema_history", "users", "magic_link_tokens", "app_refresh_tokens");
    }

    @Test
    @DisplayName("Users table should exist with correct columns")
    void usersTableShouldExistWithCorrectColumns() {
        List<String> columns = getColumnNames("users");

        assertThat(columns).containsExactlyInAnyOrder(
                "id",
                "email",
                "email_verified",
                "created_at",
                "updated_at"
        );
    }

    @Test
    @DisplayName("Magic link tokens table should exist with correct columns")
    void magicLinkTokensTableShouldExistWithCorrectColumns() {
        List<String> columns = getColumnNames("magic_link_tokens");

        assertThat(columns).containsExactlyInAnyOrder(
                "id",
                "user_id",
                "token_hash",
                "expires_at",
                "used_at",
                "created_at"
        );
    }

    @Test
    @DisplayName("App refresh tokens table should exist with correct columns")
    void appRefreshTokensTableShouldExistWithCorrectColumns() {
        List<String> columns = getColumnNames("app_refresh_tokens");

        assertThat(columns).containsExactlyInAnyOrder(
                "id",
                "user_id",
                "token_hash",
                "expires_at",
                "revoked_at",
                "user_agent",
                "ip_address",
                "created_at"
        );
    }

    @Test
    @DisplayName("Foreign keys should be configured correctly")
    void foreignKeysShouldBeConfiguredCorrectly() {
        // Verify magic_link_tokens -> users FK exists
        assertThat(foreignKeyExists("magic_link_tokens", "users"))
                .as("magic_link_tokens should have FK to users")
                .isTrue();

        // Verify app_refresh_tokens -> users FK exists
        assertThat(foreignKeyExists("app_refresh_tokens", "users"))
                .as("app_refresh_tokens should have FK to users")
                .isTrue();
    }

    @Test
    @DisplayName("Unique constraints should be configured correctly")
    void uniqueConstraintsShouldBeConfiguredCorrectly() {
        // Verify unique constraint on users.email
        assertThat(uniqueConstraintExists("users", "email"))
                .as("users.email should have unique constraint")
                .isTrue();

        // Verify unique constraint on magic_link_tokens.token_hash
        assertThat(uniqueConstraintExists("magic_link_tokens", "token_hash"))
                .as("magic_link_tokens.token_hash should have unique constraint")
                .isTrue();

        // Verify unique constraint on app_refresh_tokens.token_hash
        assertThat(uniqueConstraintExists("app_refresh_tokens", "token_hash"))
                .as("app_refresh_tokens.token_hash should have unique constraint")
                .isTrue();
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private List<String> getColumnNames(String tableName) {
        String sql = """
            SELECT column_name 
            FROM information_schema.columns 
            WHERE table_name = ? 
            AND table_schema = 'public'
            ORDER BY ordinal_position
            """;
        return jdbcTemplate.queryForList(sql, String.class, tableName);
    }

    private boolean foreignKeyExists(String fromTable, String toTable) {
        String sql = """
            SELECT COUNT(*) FROM information_schema.table_constraints tc
            JOIN information_schema.referential_constraints rc 
                ON tc.constraint_name = rc.constraint_name
            JOIN information_schema.table_constraints tc2 
                ON rc.unique_constraint_name = tc2.constraint_name
            WHERE tc.table_name = ?
            AND tc2.table_name = ?
            AND tc.constraint_type = 'FOREIGN KEY'
            """;
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, fromTable, toTable);
        return count != null && count > 0;
    }

    private boolean uniqueConstraintExists(String tableName, String columnName) {
        String sql = """
            SELECT COUNT(*) FROM information_schema.table_constraints tc
            JOIN information_schema.constraint_column_usage ccu 
                ON tc.constraint_name = ccu.constraint_name
            WHERE tc.table_name = ?
            AND ccu.column_name = ?
            AND tc.constraint_type = 'UNIQUE'
            """;
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, tableName, columnName);
        return count != null && count > 0;
    }
}
