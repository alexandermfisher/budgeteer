package dev.amf.budgeteer.api.auth.dto;

import dev.amf.budgeteer.domain.user.User;

import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO for current user information.
 */
public record UserResponse(
        UUID id,
        String email,
        boolean emailVerified,
        Instant createdAt
) {
    /**
     * Creates a UserResponse from a User entity.
     */
    public static UserResponse fromUser(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.isEmailVerified(),
                user.getCreatedAt()
        );
    }
}
