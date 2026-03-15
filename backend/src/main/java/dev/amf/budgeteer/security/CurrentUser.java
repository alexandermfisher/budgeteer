package dev.amf.budgeteer.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to inject the currently authenticated {@link dev.amf.budgeteer.domain.user.User}
 * into a controller method parameter.
 *
 * <p>Usage:
 * <pre>
 * {@code
 * @GetMapping("/me")
 * public ResponseEntity<User> getCurrentUser(@CurrentUser User user) {
 *     // user is guaranteed to be non-null and loaded from database
 *     return ResponseEntity.ok(user);
 * }
 * }
 * </pre>
 *
 * <p>This annotation triggers the {@link CurrentUserArgumentResolver} which:
 * <ol>
 *   <li>Extracts the user ID from the JWE authentication token</li>
 *   <li>Loads the full User entity from the database</li>
 *   <li>Throws {@link dev.amf.budgeteer.exception.ApiException} if not authenticated or user not found</li>
 * </ol>
 *
 * <p>For endpoints that only need the user ID without a database lookup,
 * consider using {@link CurrentUserId} instead.
 *
 * @see CurrentUserId
 * @see CurrentUserArgumentResolver
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUser {
}
