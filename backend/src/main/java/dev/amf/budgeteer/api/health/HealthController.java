package dev.amf.budgeteer.api.health;

import dev.amf.budgeteer.api.common.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;

/**
 * Health check endpoints for monitoring and load balancers.
 * These endpoints are public and do not require authentication.
 * 
 * @deprecated Use Spring Boot Actuator endpoints instead:
 *             - /actuator/health for health checks
 *             - /actuator/info for application information
 *             
 *             These legacy endpoints are maintained for backward compatibility
 *             but will be removed in a future version.
 */
@Deprecated(since = "0.0.1", forRemoval = true)
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final DataSource dataSource;
    private final String activeProfile;

    public HealthController(
            DataSource dataSource,
            @Value("${spring.profiles.active:unknown}") String activeProfile) {
        this.dataSource = dataSource;
        this.activeProfile = activeProfile;
    }

    /**
     * Simple health check - just returns OK if the app is running.
     * 
     * <p>GET /api/health
     * 
     * <p>Use this for basic load balancer health checks.
     * 
     * @deprecated Use /actuator/health instead
     */
    @Deprecated(since = "0.0.1", forRemoval = true)
    @GetMapping
    public ResponseEntity<ApiResponse<HealthResponse>> health() {
        return ResponseEntity.ok(ApiResponse.of(new HealthResponse(
                "UP",
                activeProfile,
                Instant.now().toString()
        )));
    }

    /**
     * Detailed health check - verifies database connectivity.
     * 
     * <p>GET /api/health/ready
     * 
     * <p>Use this for readiness probes (Kubernetes) or detailed monitoring.
     * 
     * @deprecated Use /actuator/health with show-details=always instead
     */
    @Deprecated(since = "0.0.1", forRemoval = true)
    @GetMapping("/ready")
    public ResponseEntity<ApiResponse<ReadinessResponse>> readiness() {
        boolean dbHealthy = checkDatabase();
        String status = dbHealthy ? "UP" : "DOWN";
        
        ReadinessResponse response = new ReadinessResponse(
                status,
                activeProfile,
                Instant.now().toString(),
                new DatabaseHealth(dbHealthy ? "UP" : "DOWN", dbHealthy ? null : "Connection failed")
        );

        if (dbHealthy) {
            return ResponseEntity.ok(ApiResponse.of(response));
        } else {
            return ResponseEntity.status(503).body(ApiResponse.of(response));
        }
    }

    /**
     * Liveness probe - just confirms the app process is alive.
     * 
     * <p>GET /api/health/live
     * 
     * <p>Use this for Kubernetes liveness probes.
     * 
     * @deprecated Use /actuator/health/liveness instead
     */
    @Deprecated(since = "0.0.1", forRemoval = true)
    @GetMapping("/live")
    public ResponseEntity<String> liveness() {
        return ResponseEntity.ok("OK");
    }

    private boolean checkDatabase() {
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(2); // 2 second timeout
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Basic health response.
     */
    public record HealthResponse(
            String status,
            String profile,
            String timestamp
    ) {}

    /**
     * Detailed readiness response with component health.
     */
    public record ReadinessResponse(
            String status,
            String profile,
            String timestamp,
            DatabaseHealth database
    ) {}

    /**
     * Database health details.
     */
    public record DatabaseHealth(
            String status,
            String error
    ) {}
}
