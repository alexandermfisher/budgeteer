package dev.amf.budgeteer.domain.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link User} entity.
 */
@DisplayName("User")
class UserTest {

    @Test
    @DisplayName("constructor should set email and default emailVerified to false")
    void constructorShouldSetDefaults() {
        User user = new User("test@example.com");

        assertThat(user.getEmail()).isEqualTo("test@example.com");
        assertThat(user.isEmailVerified()).isFalse();
        assertThat(user.getId()).isNull(); // Not yet persisted
    }

    @Test
    @DisplayName("should allow setting emailVerified")
    void shouldAllowSettingEmailVerified() {
        User user = new User("test@example.com");

        user.setEmailVerified(true);

        assertThat(user.isEmailVerified()).isTrue();
    }

    @Test
    @DisplayName("toString should include relevant fields")
    void toStringShouldIncludeRelevantFields() {
        User user = new User("test@example.com");

        String result = user.toString();

        assertThat(result).contains("test@example.com");
        assertThat(result).contains("emailVerified");
    }
}
