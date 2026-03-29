package dev.amf.budgeteer.repository;

import dev.amf.budgeteer.domain.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository for User entity operations.
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Finds a user by their email address (case-insensitive).
     *
     * @param email the email address to search for
     * @return the user if found
     */
    Optional<User> findByEmailIgnoreCase(String email);

    /**
     * Checks if a user exists with the given email address.
     *
     * @param email the email address to check
     * @return true if a user exists with this email
     */
    boolean existsByEmailIgnoreCase(String email);
}
