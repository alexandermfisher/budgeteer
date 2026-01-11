package dev.amf.budgeteer.integration.repository;

import dev.amf.budgeteer.domain.user.User;
import dev.amf.budgeteer.domain.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link UserRepository}.
 * Uses H2 in-memory database via @DataJpaTest.
 */
@DataJpaTest
@ActiveProfiles("test")
@DisplayName("UserRepository Integration Tests")
class UserRepositoryIT {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager entityManager;

    // findByEmailIgnoreCase tests

    @Test
    @DisplayName("findByEmailIgnoreCase - should find user by exact email")
    void findByEmailIgnoreCase_shouldFindByExactEmail() {
        User user = new User("test@example.com");
        entityManager.persistAndFlush(user);
        entityManager.clear();

        Optional<User> found = userRepository.findByEmailIgnoreCase("test@example.com");

        assertThat(found).isPresent();
        assertThat(found.get().getEmail()).isEqualTo("test@example.com");
    }

    @Test
    @DisplayName("findByEmailIgnoreCase - should find user case-insensitively")
    void findByEmailIgnoreCase_shouldFindCaseInsensitive() {
        User user = new User("Test@Example.COM");
        entityManager.persistAndFlush(user);
        entityManager.clear();

        Optional<User> found = userRepository.findByEmailIgnoreCase("test@example.com");

        assertThat(found).isPresent();
        assertThat(found.get().getEmail()).isEqualTo("Test@Example.COM");
    }

    @Test
    @DisplayName("findByEmailIgnoreCase - should return empty when email not found")
    void findByEmailIgnoreCase_shouldReturnEmptyWhenNotFound() {
        Optional<User> found = userRepository.findByEmailIgnoreCase("nonexistent@example.com");
        assertThat(found).isEmpty();
    }

    // existsByEmailIgnoreCase tests

    @Test
    @DisplayName("existsByEmailIgnoreCase - should return true when user exists")
    void existsByEmailIgnoreCase_shouldReturnTrueWhenExists() {
        User user = new User("exists@example.com");
        entityManager.persistAndFlush(user);
        entityManager.clear();

        assertThat(userRepository.existsByEmailIgnoreCase("exists@example.com")).isTrue();
        assertThat(userRepository.existsByEmailIgnoreCase("EXISTS@EXAMPLE.COM")).isTrue();
    }

    @Test
    @DisplayName("existsByEmailIgnoreCase - should return false when user does not exist")
    void existsByEmailIgnoreCase_shouldReturnFalseWhenNotExists() {
        assertThat(userRepository.existsByEmailIgnoreCase("nobody@example.com")).isFalse();
    }

    // Email Uniqueness Constraint tests

    @Test
    @DisplayName("Email constraint - should enforce unique email")
    void emailConstraint_shouldEnforceUniqueEmail() {
        User user1 = new User("unique@example.com");
        entityManager.persistAndFlush(user1);

        User user2 = new User("unique@example.com");

        // Hibernate throws ConstraintViolationException, Spring wraps it in PersistenceException
        assertThatThrownBy(() -> {
            entityManager.persistAndFlush(user2);
        }).isInstanceOf(jakarta.persistence.PersistenceException.class);
    }

    // Entity Lifecycle tests

    @Test
    @DisplayName("Entity lifecycle - should set createdAt and updatedAt on persist")
    void entityLifecycle_shouldSetTimestampsOnPersist() {
        User user = new User("timestamps@example.com");

        entityManager.persistAndFlush(user);

        assertThat(user.getCreatedAt()).isNotNull();
        assertThat(user.getUpdatedAt()).isNotNull();
        assertThat(user.getCreatedAt()).isEqualTo(user.getUpdatedAt());
    }

    @Test
    @DisplayName("Entity lifecycle - should generate UUID on persist")
    void entityLifecycle_shouldGenerateId() {
        User user = new User("uuid@example.com");
        assertThat(user.getId()).isNull();

        entityManager.persistAndFlush(user);

        assertThat(user.getId()).isNotNull();
    }

    @Test
    @DisplayName("Entity lifecycle - should default emailVerified to false")
    void entityLifecycle_shouldDefaultEmailVerifiedToFalse() {
        User user = new User("verify@example.com");

        entityManager.persistAndFlush(user);

        assertThat(user.isEmailVerified()).isFalse();
    }
}
