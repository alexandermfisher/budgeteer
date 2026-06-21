package dev.amfshr.budgeteer.api.monzo;

import dev.amfshr.budgeteer.api.common.ApiResponse;
import dev.amfshr.budgeteer.api.common.ErrorCode;
import dev.amfshr.budgeteer.api.monzo.dto.MonzoConnectInitResponse;
import dev.amfshr.budgeteer.api.monzo.dto.MonzoConnectionResponse;
import dev.amfshr.budgeteer.api.monzo.dto.MonzoStatusResponse;
import dev.amfshr.budgeteer.api.monzo.dto.MonzoSyncProgressResponse;
import dev.amfshr.budgeteer.domain.monzo.MonzoConnection;
import dev.amfshr.budgeteer.domain.user.User;
import dev.amfshr.budgeteer.exception.ApiException;
import dev.amfshr.budgeteer.security.CurrentUser;
import dev.amfshr.budgeteer.service.monzo.MonzoConnectionService;
import dev.amfshr.budgeteer.service.monzo.MonzoOAuthService;
import dev.amfshr.budgeteer.service.monzo.TransactionSyncService;
import dev.amfshr.budgeteer.util.LogSanitizer;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
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
@Validated
@RestController
@RequestMapping("/api/monzo")
public class MonzoController {

    private static final Logger log = LoggerFactory.getLogger(MonzoController.class);

    private final MonzoOAuthService oauthService;
    private final MonzoConnectionService connectionService;
    private final TransactionSyncService syncService;

    public MonzoController(
            MonzoOAuthService oauthService,
            MonzoConnectionService connectionService,
            TransactionSyncService syncService
    ) {
        this.oauthService = oauthService;
        this.connectionService = connectionService;
        this.syncService = syncService;
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
     * @param user the authenticated user (injected via {@link CurrentUser})
     * @return redirect to Monzo authorization page
     */
    @GetMapping("/connect")
    public RedirectView initiateOAuth(@CurrentUser User user) {
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
     * @param user the authenticated user (injected via {@link CurrentUser})
     * @return the authorization URL in a JSON response
     */
    @PostMapping("/connect")
    public ResponseEntity<ApiResponse<MonzoConnectInitResponse>> initiateOAuthJson(
            @CurrentUser User user
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
     * <p>Success case: GET /api/monzo/callback?code=...&state=...
     * <p>Error case: GET /api/monzo/callback?error=...&error_description=...&state=...
     *
     * @param code             the authorization code from Monzo (null if user denied)
     * @param state            the state parameter for CSRF verification
     * @param error            OAuth error code if user denied access
     * @param errorDescription human-readable error description
     * @return redirect to success page or JSON response
     */
    @GetMapping("/callback")
    public ResponseEntity<ApiResponse<MonzoConnectionResponse>> handleCallback(
            // todo : potentially extract field validation - unsure as get request with query params
            @RequestParam(value = "code", required = false) @Nullable @Size(max = 1024, message = "Code too long") String code,
            @RequestParam("state") @NotBlank(message = "State is required") @Size(max = 64, message = "State too long") String state,
            @RequestParam(value = "error", required = false) @Nullable @Size(max = 256, message = "Error too long") String error,
            @RequestParam(value = "error_description", required = false) @Nullable @Size(max = 1000, message = "Error description too long") String errorDescription
    ) {
        log.info("Received Monzo OAuth callback");

        // First verify state to get the user (even for error cases)
        User user = oauthService.verifyStateAndGetUser(state);
        log.debug("OAuth callback for user {}", user.getId());

        // Check if user denied access
        if (error != null) {
            // Sanitize user-controlled input to prevent log injection attacks
            log.warn("Monzo OAuth denied by user {} [error={}, description={}]",
                    user.getId(),
                    LogSanitizer.sanitize(error, 50),
                    LogSanitizer.sanitize(errorDescription, 200));
            throw new ApiException(
                    ErrorCode.OAUTH_ACCESS_DENIED,
                    errorDescription != null ? errorDescription : "User denied access to Monzo account"
            );
        }

        // Ensure code is present
        if (code == null || code.isBlank()) {
            log.error("Monzo OAuth callback missing authorization code for user {}", user.getId());
            throw new ApiException(
                    ErrorCode.OAUTH_CODE_MISSING,
                    "Authorization code is required"
            );
        }

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

        log.info("Triggering async transaction backfill within SCA window [connectionId={}]",
                connection.getId());
        syncService.backfillAsync(connection.getId());

        // Return immediately — backfill happens async with retries in background
        return ResponseEntity.ok(ApiResponse.of(MonzoConnectionResponse.from(connection)));
    }

    // ============ Connection Management Endpoints ============

    /**
     * Lists all active Monzo connections for the authenticated user.
     *
     * <p>GET /api/monzo/connections
     *
     * @param user the authenticated user (injected via {@link CurrentUser})
     * @return list of connections (without tokens)
     */
    @GetMapping("/connections")
    public ResponseEntity<ApiResponse<List<MonzoConnectionResponse>>> listConnections(
            @CurrentUser User user
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
     * @param user the authenticated user (injected via {@link CurrentUser})
     * @param id   the connection ID
     * @return the connection details (without tokens)
     */
    @GetMapping("/connections/{id}")
    public ResponseEntity<ApiResponse<MonzoConnectionResponse>> getConnection(
            @CurrentUser User user,
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
     * @param user the authenticated user (injected via {@link CurrentUser})
     * @param id   the connection ID
     * @return 204 No Content on success
     */
    @DeleteMapping("/connections/{id}")
    public ResponseEntity<Void> disconnectConnection(
            @CurrentUser User user,
            @PathVariable UUID id
    ) {
        log.info("User {} disconnecting Monzo connection {}", user.getId(), id);

        connectionService.disconnectConnection(id, user.getId());

        return ResponseEntity.noContent().build();
    }

    /**
     * Gets the connection status for the authenticated user.
     *
     * <p>Returns whether the user has an active Monzo connection and the health
     * of their OAuth tokens. The {@code tokenStatus} field values are:
     * <ul>
     *   <li>{@code ACTIVE} — connected, token valid</li>
     *   <li>{@code EXPIRING_SOON} — connected, token expiring within 5 minutes</li>
     *   <li>{@code RECONNECT_REQUIRED} — no active connection, re-OAuth needed</li>
     * </ul>
     *
     * <p>GET /api/monzo/status
     *
     * @param user the authenticated user (injected via {@link CurrentUser})
     * @return connection status
     */
    /**
     * Returns per-account backfill progress for the authenticated user.
     * Progress is time-based: (now - currentWindowDate) / (now - accountCreatedAt).
     * Poll this during backfill to drive a progress indicator in the UI.
     *
     * <p>GET /api/monzo/sync/progress
     */
    @GetMapping("/sync/progress")
    public ResponseEntity<ApiResponse<MonzoSyncProgressResponse>> getSyncProgress(@CurrentUser User user) {
        MonzoSyncProgressResponse progress = syncService.getSyncProgress(user.getId());
        return ResponseEntity.ok(ApiResponse.of(progress));
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<MonzoStatusResponse>> getStatus(@CurrentUser User user) {
        boolean hasConnection = connectionService.hasActiveConnection(user.getId());
        long connectionCount = connectionService.countActiveConnections(user.getId());
        MonzoConnectionService.TokenStatus tokenStatus = connectionService.getTokenStatus(user.getId());
        MonzoConnectionService.BackfillStatus backfillStatus = connectionService.getBackfillStatus(user.getId());

        MonzoStatusResponse status = new MonzoStatusResponse(hasConnection, connectionCount, tokenStatus, backfillStatus);
        return ResponseEntity.ok(ApiResponse.of(status));
    }
}
