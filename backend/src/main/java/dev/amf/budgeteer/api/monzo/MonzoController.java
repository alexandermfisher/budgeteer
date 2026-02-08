package dev.amf.budgeteer.api.monzo;

import dev.amf.budgeteer.api.common.ApiResponse;
import dev.amf.budgeteer.api.monzo.dto.MonzoConnectInitResponse;
import dev.amf.budgeteer.api.monzo.dto.MonzoConnectionResponse;
import dev.amf.budgeteer.domain.monzo.MonzoConnection;
import dev.amf.budgeteer.domain.user.User;
import dev.amf.budgeteer.service.MonzoConnectionService;
import dev.amf.budgeteer.service.MonzoOAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

import java.util.List;
import java.util.UUID;

/**
 * Controller for Monzo bank account integration.
 *
 * <p>Provides endpoints for:
 * <ul>
 *   <li>OAuth flow initiation and callback handling</li>
 *   <li>Connection management (list, view, disconnect)</li>
 * </ul>
 *
 * <p>Most endpoints require authentication. The OAuth callback is public but
 * uses database-stored state for CSRF protection and user association.
 */
@RestController
@RequestMapping("/api/monzo")
public class MonzoController {

    private static final Logger log = LoggerFactory.getLogger(MonzoController.class);

    private final MonzoOAuthService oauthService;
    private final MonzoConnectionService connectionService;

    public MonzoController(
            MonzoOAuthService oauthService,
            MonzoConnectionService connectionService
    ) {
        this.oauthService = oauthService;
        this.connectionService = connectionService;
    }

    // ============ OAuth Endpoints ============

    /**
     * Initiates the Monzo OAuth flow.
     *
     * <p>This endpoint:
     * <ol>
     *   <li>Generates a secure random state token</li>
     *   <li>Stores the state with user association in the database</li>
     *   <li>Redirects the user to Monzo's authorization page</li>
     * </ol>
     *
     * <p>After the user approves, Monzo redirects to /api/monzo/callback.
     *
     * <p>GET /api/monzo/connect
     *
     * @param user the authenticated user (injected by Spring Security)
     * @return redirect to Monzo authorization page
     */
    @GetMapping("/connect")
    public RedirectView initiateOAuth(@AuthenticationPrincipal User user) {
        log.info("User {} initiating Monzo OAuth flow", user.getId());

        String authorizationUrl = oauthService.initiateOAuthFlow(user);

        log.debug("Redirecting to Monzo OAuth: {}", authorizationUrl);
        return new RedirectView(authorizationUrl);
    }

    /**
     * Alternative endpoint that returns JSON instead of redirecting.
     *
     * <p>Useful for SPAs that want to control the redirect themselves.
     *
     * <p>POST /api/monzo/connect
     *
     * @param user the authenticated user
     * @return the authorization URL in a JSON response
     */
    @PostMapping("/connect")
    public ResponseEntity<ApiResponse<MonzoConnectInitResponse>> initiateOAuthJson(
            @AuthenticationPrincipal User user
    ) {
        log.info("User {} initiating Monzo OAuth flow (JSON response)", user.getId());

        String authorizationUrl = oauthService.initiateOAuthFlow(user);

        MonzoConnectInitResponse response = new MonzoConnectInitResponse(
                "Redirect to authorization URL to connect your Monzo account",
                authorizationUrl
        );

        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * Handles the OAuth callback from Monzo.
     *
     * <p>This endpoint is PUBLIC because the browser is redirected here by Monzo.
     * However, security is maintained through the state parameter which links
     * the callback to a specific authenticated user.
     *
     * <p>GET /api/monzo/callback?code=...&state=...
     *
     * @param code  the authorization code from Monzo
     * @param state the state parameter for CSRF verification
     * @return redirect to success page or JSON response
     */
    @GetMapping("/callback")
    public ResponseEntity<ApiResponse<MonzoConnectionResponse>> handleCallback(
            @RequestParam("code") String code,
            @RequestParam("state") String state
    ) {
        log.info("Received Monzo OAuth callback");

        // Verify state and get associated user
        User user = oauthService.verifyStateAndGetUser(state);
        log.debug("OAuth callback for user {}", user.getId());

        // Exchange code for tokens
        MonzoOAuthService.TokenResponse tokens = oauthService.exchangeCodeForTokens(code);

        // Get Monzo user ID
        String monzoUserId = oauthService.getMonzoUserId(tokens.accessToken());

        // Save connection (tokens are encrypted by MonzoConnectionService)
        MonzoConnection connection = connectionService.createConnection(
                user.getId(),
                monzoUserId,
                tokens.accessToken(),
                tokens.refreshToken(),
                tokens.expiresAt()
        );

        log.info("Successfully connected Monzo account for user {} [connectionId={}]",
                user.getId(), connection.getId());

        // Return JSON response (frontend can redirect based on this)
        // In the future, could redirect to a frontend URL
        return ResponseEntity.ok(ApiResponse.of(MonzoConnectionResponse.from(connection)));
    }

    // ============ Connection Management Endpoints ============

    /**
     * Lists all active Monzo connections for the authenticated user.
     *
     * <p>GET /api/monzo/connections
     *
     * @param user the authenticated user
     * @return list of connections (without tokens)
     */
    @GetMapping("/connections")
    public ResponseEntity<ApiResponse<List<MonzoConnectionResponse>>> listConnections(
            @AuthenticationPrincipal User user
    ) {
        log.debug("Listing Monzo connections for user {}", user.getId());

        List<MonzoConnectionResponse> connections = connectionService
                .listActiveConnections(user.getId())
                .stream()
                .map(MonzoConnectionResponse::from)
                .toList();

        return ResponseEntity.ok(ApiResponse.of(connections));
    }

    /**
     * Gets a specific Monzo connection by ID.
     *
     * <p>GET /api/monzo/connections/{id}
     *
     * @param user the authenticated user
     * @param id   the connection ID
     * @return the connection details (without tokens)
     */
    @GetMapping("/connections/{id}")
    public ResponseEntity<ApiResponse<MonzoConnectionResponse>> getConnection(
            @AuthenticationPrincipal User user,
            @PathVariable UUID id
    ) {
        log.debug("Getting Monzo connection {} for user {}", id, user.getId());

        MonzoConnection connection = connectionService.getConnection(id, user.getId());

        return ResponseEntity.ok(ApiResponse.of(MonzoConnectionResponse.from(connection)));
    }

    /**
     * Disconnects (soft-deletes) a Monzo connection.
     *
     * <p>The connection is marked as disconnected but not deleted from the database.
     * This maintains audit history and allows for potential reconnection.
     *
     * <p>DELETE /api/monzo/connections/{id}
     *
     * @param user the authenticated user
     * @param id   the connection ID
     * @return 204 No Content on success
     */
    @DeleteMapping("/connections/{id}")
    public ResponseEntity<Void> disconnectConnection(
            @AuthenticationPrincipal User user,
            @PathVariable UUID id
    ) {
        log.info("User {} disconnecting Monzo connection {}", user.getId(), id);

        connectionService.disconnectConnection(id, user.getId());

        return ResponseEntity.noContent().build();
    }

    /**
     * Gets the connection status for the authenticated user.
     *
     * <p>Quick endpoint to check if a user has any active Monzo connections.
     *
     * <p>GET /api/monzo/status
     *
     * @param user the authenticated user
     * @return connection status
     */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse<ConnectionStatus>> getStatus(
            @AuthenticationPrincipal User user
    ) {
        boolean hasConnection = connectionService.hasActiveConnection(user.getId());
        long connectionCount = connectionService.countActiveConnections(user.getId());

        ConnectionStatus status = new ConnectionStatus(hasConnection, connectionCount);
        return ResponseEntity.ok(ApiResponse.of(status));
    }

    // ============ Response Records ============

    /**
     * Simple status response for connection check.
     */
    public record ConnectionStatus(
            boolean connected,
            long connectionCount
    ) {
    }
}
