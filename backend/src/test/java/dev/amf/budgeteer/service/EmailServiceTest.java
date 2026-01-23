package dev.amf.budgeteer.service;

import dev.amf.budgeteer.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EmailService}.
 * 
 * <p>Uses Mockito to mock the JavaMailSender and AppProperties dependencies.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("EmailService")
class EmailServiceTest {

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private AppProperties appProperties;

    private EmailService emailService;

    @BeforeEach
    void setUp() {
        emailService = new EmailService(mailSender, appProperties);
    }

    @Nested
    @DisplayName("sendMagicLinkEmail")
    class SendMagicLinkEmail {

        @Test
        @DisplayName("should send email when email is enabled")
        void shouldSendEmailWhenEnabled() {
            // Given
            String email = "test@example.com";
            String token = "magic-token-123";
            when(appProperties.isEmailEnabled()).thenReturn(true);
            when(appProperties.getBaseUrl()).thenReturn("https://budgeteer.dev");

            // When
            emailService.sendMagicLinkEmail(email, token);

            // Then
            verify(mailSender).send(any(SimpleMailMessage.class));
        }

        @Test
        @DisplayName("should not send email when email is disabled (dev mode)")
        void shouldNotSendEmailWhenDisabled() {
            // Given
            String email = "test@example.com";
            String token = "magic-token-123";
            when(appProperties.isEmailEnabled()).thenReturn(false);
            when(appProperties.getBaseUrl()).thenReturn("http://localhost:8080");

            // When
            emailService.sendMagicLinkEmail(email, token);

            // Then
            verify(mailSender, never()).send(any(SimpleMailMessage.class));
        }

        @Test
        @DisplayName("should build correct magic link URL")
        void shouldBuildCorrectMagicLinkUrl() {
            // Given
            String email = "test@example.com";
            String token = "my-secret-token";
            when(appProperties.isEmailEnabled()).thenReturn(true);
            when(appProperties.getBaseUrl()).thenReturn("https://budgeteer.dev");
            
            ArgumentCaptor<SimpleMailMessage> messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);

            // When
            emailService.sendMagicLinkEmail(email, token);

            // Then
            verify(mailSender).send(messageCaptor.capture());
            SimpleMailMessage sentMessage = messageCaptor.getValue();
            assertThat(sentMessage.getText())
                    .contains("https://budgeteer.dev/api/auth/verify?token=my-secret-token");
        }

        @Test
        @DisplayName("should set correct recipient")
        void shouldSetCorrectRecipient() {
            // Given
            String email = "recipient@example.com";
            String token = "token-123";
            when(appProperties.isEmailEnabled()).thenReturn(true);
            when(appProperties.getBaseUrl()).thenReturn("https://budgeteer.dev");
            
            ArgumentCaptor<SimpleMailMessage> messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);

            // When
            emailService.sendMagicLinkEmail(email, token);

            // Then
            verify(mailSender).send(messageCaptor.capture());
            SimpleMailMessage sentMessage = messageCaptor.getValue();
            assertThat(sentMessage.getTo()).containsExactly("recipient@example.com");
        }

        @Test
        @DisplayName("should set correct subject")
        void shouldSetCorrectSubject() {
            // Given
            String email = "test@example.com";
            String token = "token-123";
            when(appProperties.isEmailEnabled()).thenReturn(true);
            when(appProperties.getBaseUrl()).thenReturn("https://budgeteer.dev");
            
            ArgumentCaptor<SimpleMailMessage> messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);

            // When
            emailService.sendMagicLinkEmail(email, token);

            // Then
            verify(mailSender).send(messageCaptor.capture());
            SimpleMailMessage sentMessage = messageCaptor.getValue();
            assertThat(sentMessage.getSubject()).isEqualTo("Login to Budgeteer");
        }

        @Test
        @DisplayName("should set correct from address")
        void shouldSetCorrectFromAddress() {
            // Given
            String email = "test@example.com";
            String token = "token-123";
            when(appProperties.isEmailEnabled()).thenReturn(true);
            when(appProperties.getBaseUrl()).thenReturn("https://budgeteer.dev");
            
            ArgumentCaptor<SimpleMailMessage> messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);

            // When
            emailService.sendMagicLinkEmail(email, token);

            // Then
            verify(mailSender).send(messageCaptor.capture());
            SimpleMailMessage sentMessage = messageCaptor.getValue();
            assertThat(sentMessage.getFrom()).isEqualTo("noreply@budgeteer.dev");
        }

        @Test
        @DisplayName("should include expiry information in email body")
        void shouldIncludeExpiryInfo() {
            // Given
            String email = "test@example.com";
            String token = "token-123";
            when(appProperties.isEmailEnabled()).thenReturn(true);
            when(appProperties.getBaseUrl()).thenReturn("https://budgeteer.dev");
            
            ArgumentCaptor<SimpleMailMessage> messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);

            // When
            emailService.sendMagicLinkEmail(email, token);

            // Then
            verify(mailSender).send(messageCaptor.capture());
            SimpleMailMessage sentMessage = messageCaptor.getValue();
            assertThat(sentMessage.getText()).contains("15 minutes");
        }

        @Test
        @DisplayName("should throw RuntimeException when mail sending fails")
        void shouldThrowExceptionWhenMailFails() {
            // Given
            String email = "test@example.com";
            String token = "token-123";
            when(appProperties.isEmailEnabled()).thenReturn(true);
            when(appProperties.getBaseUrl()).thenReturn("https://budgeteer.dev");
            doThrow(new MailSendException("SMTP connection failed"))
                    .when(mailSender).send(any(SimpleMailMessage.class));

            // When/Then
            assertThatThrownBy(() -> emailService.sendMagicLinkEmail(email, token))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("Failed to send email")
                    .hasCauseInstanceOf(MailSendException.class);
        }

        @Test
        @DisplayName("should handle different base URLs correctly")
        void shouldHandleDifferentBaseUrls() {
            // Given - localhost for dev
            String email = "test@example.com";
            String token = "dev-token";
            when(appProperties.isEmailEnabled()).thenReturn(true);
            when(appProperties.getBaseUrl()).thenReturn("http://localhost:8080");
            
            ArgumentCaptor<SimpleMailMessage> messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);

            // When
            emailService.sendMagicLinkEmail(email, token);

            // Then
            verify(mailSender).send(messageCaptor.capture());
            SimpleMailMessage sentMessage = messageCaptor.getValue();
            assertThat(sentMessage.getText())
                    .contains("http://localhost:8080/api/auth/verify?token=dev-token");
        }
    }
}
