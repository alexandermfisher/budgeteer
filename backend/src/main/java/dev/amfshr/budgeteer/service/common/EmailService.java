package dev.amfshr.budgeteer.service.common;

import dev.amfshr.budgeteer.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Service for sending emails.
 * When email is disabled (development mode), logs the email content to console.
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final AppProperties appProperties;

    public EmailService(JavaMailSender mailSender, AppProperties appProperties) {
        this.mailSender = mailSender;
        this.appProperties = appProperties;
    }

    /**
     * Sends a magic link email to the user.
     *
     * @param email the recipient email address
     * @param token the magic link token (plain, not hashed)
     */
    public void sendMagicLinkEmail(String email, String token) {
        String magicLink = buildMagicLink(token);
        
        if (appProperties.isEmailEnabled()) {
            sendEmail(email, "Login to Budgeteer", buildMagicLinkEmailBody(magicLink));
        } else {
            // Development mode - log to console
            logMagicLink(email, magicLink);
        }
    }

    /**
     * Builds the magic link URL.
     */
    private String buildMagicLink(String token) {
        return appProperties.getBaseUrl() + "/api/v1/auth/verify?token=" + token;
    }

    /**
     * Builds the email body for magic link emails.
     */
    private String buildMagicLinkEmailBody(String magicLink) {
        return """
                Hi there,
                
                Click the link below to log in to Budgeteer:
                
                %s
                
                This link will expire in 15 minutes.
                
                If you didn't request this email, you can safely ignore it.
                
                - The Budgeteer Team
                """.formatted(magicLink);
    }

    /**
     * Logs the magic link to console (for development).
     */
    private void logMagicLink(String email, String magicLink) {
        log.info("""
                
                ╔══════════════════════════════════════════════════════════════════╗
                ║                    MAGIC LINK (DEV MODE)                         ║
                ╠══════════════════════════════════════════════════════════════════╣
                ║ Email: {}
                ║ Link:  {}
                ╚══════════════════════════════════════════════════════════════════╝
                """, email, magicLink);
    }

    /**
     * Sends an email.
     *
     * @param to      recipient email address
     * @param subject email subject
     * @param body    email body
     */
    private void sendEmail(String to, String subject, String body) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            message.setFrom(appProperties.getMail().getFrom());
            
            mailSender.send(message);
            log.info("Email sent to {} from {}", to, appProperties.getMail().getFrom());
        } catch (Exception e) {
            log.error("Failed to send email to {}: {}", to, e.getMessage());
            throw new RuntimeException("Failed to send email", e);
        }
    }
}
