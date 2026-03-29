package dev.amf.budgeteer.security;

import dev.amf.budgeteer.api.common.ErrorCode;
import dev.amf.budgeteer.domain.user.User;
import dev.amf.budgeteer.exception.ApiException;
import dev.amf.budgeteer.security.JweAuthenticationFilter.JweAuthentication;
import dev.amf.budgeteer.service.auth.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.UUID;

/**
 * Resolves controller method parameters annotated with {@link CurrentUser} or {@link CurrentUserId}.
 *
 * <p>This resolver handles two annotations:
 * <ul>
 *   <li>{@link CurrentUser} - Returns the full {@link User} entity (requires database lookup)</li>
 *   <li>{@link CurrentUserId} - Returns just the {@link UUID} (no database lookup)</li>
 * </ul>
 *
 * <p>Both annotations require the user to be authenticated with a valid JWE token.
 * If the user is not authenticated, an {@link ApiException} with {@link ErrorCode#NOT_AUTHENTICATED}
 * is thrown. If the user ID from the token doesn't exist in the database (for @CurrentUser),
 * an {@link ApiException} with {@link ErrorCode#USER_NOT_FOUND} is thrown.
 *
 * @see CurrentUser
 * @see CurrentUserId
 */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    private static final Logger log = LoggerFactory.getLogger(CurrentUserArgumentResolver.class);

    private final AuthService authService;

    public CurrentUserArgumentResolver(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        // Support @CurrentUser with User type
        if (parameter.hasParameterAnnotation(CurrentUser.class)
                && User.class.isAssignableFrom(parameter.getParameterType())) {
            return true;
        }

        // Support @CurrentUserId with UUID type
        if (parameter.hasParameterAnnotation(CurrentUserId.class)
                && UUID.class.isAssignableFrom(parameter.getParameterType())) {
            return true;
        }

        return false;
    }

    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory
    ) {
        JweAuthentication jweAuth = getJweAuthentication();

        // Handle @CurrentUserId - just return the UUID
        if (parameter.hasParameterAnnotation(CurrentUserId.class)) {
            log.debug("Resolving @CurrentUserId for user {}", jweAuth.getUserId());
            return jweAuth.getUserId();
        }

        // Handle @CurrentUser - load full User entity
        if (parameter.hasParameterAnnotation(CurrentUser.class)) {
            UUID userId = jweAuth.getUserId();
            log.debug("Resolving @CurrentUser for user {}", userId);

            return authService.getUserById(userId)
                    .orElseThrow(() -> {
                        log.warn("User from token not found in database [userId={}]", userId);
                        return new ApiException(ErrorCode.USER_NOT_FOUND);
                    });
        }

        // Should not reach here due to supportsParameter check
        throw new IllegalStateException("Unsupported parameter type for CurrentUserArgumentResolver");
    }

    /**
     * Extracts the JWE authentication from the security context.
     *
     * @return the JWE authentication
     * @throws ApiException if user is not authenticated or authentication is not JWE-based
     */
    private JweAuthentication getJweAuthentication() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (!(auth instanceof JweAuthentication jweAuth)) {
            log.debug("Attempted to resolve current user but not authenticated with JWE");
            throw new ApiException(ErrorCode.NOT_AUTHENTICATED);
        }

        return jweAuth;
    }
}
