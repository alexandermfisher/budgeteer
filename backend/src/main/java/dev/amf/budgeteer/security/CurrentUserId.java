package dev.amf.budgeteer.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to inject the currently authenticated user's ID into a controller method parameter.
 *
 * <p>Usage:
 * <pre>
 * {@code
 * @GetMapping("/connections")
 * public ResponseEntity<List<Connection>> getConnections(@CurrentUserId UUID userId) {
 *     // userId is extracted directly from the token - no database lookup
 *     return connectionService.findByUserId(userId);
 * }
 * }
 * </pre>
 *
 * <p>This annotation is more efficient than {@link CurrentUser} when you only need
 * the user ID, as it extracts the ID directly from the JWT claims without hitting
 * the database.
 *
 * <p>This annotation triggers the {@link CurrentUserArgumentResolver} which:
 * <ol>
 *   <li>Extracts the user ID from the JWE authentication token</li>
 *   <li>Returns the UUID directly (no database lookup)</li>
 *   <li>Throws {@link dev.amf.budgeteer.exception.ApiException} if not authenticated</li>
 * </ol>
 *
 * @see CurrentUser
 * @see CurrentUserArgumentResolver
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUserId {
}
