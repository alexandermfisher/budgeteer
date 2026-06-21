package dev.amfshr.budgeteer.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Development-only component that cleans the database on startup.
 * 
 * <p>This is useful during development when you want to test flows
 * (like user registration) with a fresh database each time.
 * 
 * <p>Control via application-dev.properties:
 * <pre>
 * app.database.clean-on-startup=true   # Wipe DB on every start
 * app.database.clean-on-startup=false  # Keep data between restarts
 * </pre>
 * 
 * <p><strong>WARNING:</strong> This is only active in the 'dev' profile.
 * It will never run in production.
 */
@Component
@Profile("dev")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DevDatabaseCleaner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDatabaseCleaner.class);

    private final DataSource dataSource;
    private final boolean cleanOnStartup;

    public DevDatabaseCleaner(
            DataSource dataSource,
            @Value("${app.database.clean-on-startup:false}") boolean cleanOnStartup) {
        this.dataSource = dataSource;
        this.cleanOnStartup = cleanOnStartup;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (cleanOnStartup) {
            log.warn("╔════════════════════════════════════════════════════════════╗");
            log.warn("║  🧹 CLEANING DATABASE - All data will be wiped!            ║");
            log.warn("║  Set app.database.clean-on-startup=false to disable        ║");
            log.warn("╚════════════════════════════════════════════════════════════╝");
            
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .cleanDisabled(false)
                    .load();
            
            flyway.clean();
            flyway.migrate();
            log.info("✅ Fresh database ready!");
        }
    }
}
