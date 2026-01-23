package dev.amf.budgeteer.service;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.DirectDecrypter;
import com.nimbusds.jose.crypto.DirectEncrypter;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.EncryptedJWT;
import dev.amf.budgeteer.config.JweProperties;
import dev.amf.budgeteer.domain.user.User;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.text.ParseException;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for creating and validating JWE (JSON Web Encryption) tokens.
 * Uses AES-256-GCM encryption for secure, encrypted session tokens.
 */
@Service
public class JweTokenService {

    private static final Logger log = LoggerFactory.getLogger(JweTokenService.class);

    private final JweProperties jweProperties;
    private SecretKey secretKey;

    public JweTokenService(JweProperties jweProperties) {
        this.jweProperties = jweProperties;
    }

    @PostConstruct
    public void init() {
        String keyString = jweProperties.getSecretKey();
        if (keyString == null || keyString.isBlank()) {
            throw new IllegalStateException(
                    "JWE_SECRET_KEY is not configured! " +
                    "Please set the JWE_SECRET_KEY environment variable. " +
                    "Generate with: openssl rand -base64 32"
            );
        }
        
        byte[] keyBytes = Base64.getDecoder().decode(keyString);
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException(
                    "JWE_SECRET_KEY must be 32 bytes (256 bits) when base64 decoded. " +
                    "Current length: " + keyBytes.length + " bytes"
            );
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
        log.info("JWE token service initialized successfully");
    }

    /**
     * Creates an encrypted JWE access token for a user.
     *
     * @param user the user to create a token for
     * @return the encrypted JWE token string
     */
    public String createAccessToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(jweProperties.getAccessTokenExpiry());

        String tokenId = UUID.randomUUID().toString();
        
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiry))
                .jwtID(tokenId)
                .build();

        String token = encryptClaims(claimsSet);
        
        log.debug("Access token created [userId={}, expiresAt={}]", 
                user.getId(), expiry);

        return token;
    }

    /**
     * Validates and decrypts a JWE access token.
     *
     * @param token the JWE token string
     * @return the claims if valid, empty if invalid or expired
     */
    public Optional<TokenClaims> validateAccessToken(String token) {
        return decryptAndValidate(token);
    }

    /**
     * Decrypts a JWE token and validates its claims.
     *
     * @param token the JWE token string
     * @return the claims if valid, empty if invalid or expired
     */
    private Optional<TokenClaims> decryptAndValidate(String token) {
        try {
            EncryptedJWT jwt = EncryptedJWT.parse(token);
            
            // Decrypt
            JWEDecrypter decrypter = new DirectDecrypter(secretKey);
            jwt.decrypt(decrypter);

            JWTClaimsSet claims = jwt.getJWTClaimsSet();

            // Check expiration
            Date expiration = claims.getExpirationTime();
            if (expiration == null || expiration.before(new Date())) {
                log.debug("Token expired");
                return Optional.empty();
            }

            // Extract claims
            String subject = claims.getSubject();
            String email = claims.getStringClaim("email");
            String jti = claims.getJWTID();

            if (subject == null || email == null) {
                log.debug("Token missing required claims");
                return Optional.empty();
            }

            return Optional.of(new TokenClaims(
                    UUID.fromString(subject),
                    email,
                    claims.getIssueTime().toInstant(),
                    expiration.toInstant(),
                    jti
            ));

        } catch (ParseException e) {
            log.debug("Failed to parse token: {}", e.getMessage());
            return Optional.empty();
        } catch (JOSEException e) {
            log.debug("Failed to decrypt token: {}", e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.debug("Token validation failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Encrypts JWT claims into a JWE token string.
     */
    private String encryptClaims(JWTClaimsSet claimsSet) {
        try {
            // Create JWE header
            JWEHeader header = new JWEHeader.Builder(JWEAlgorithm.DIR, EncryptionMethod.A256GCM)
                    .contentType("JWT")
                    .build();

            // Create encrypted JWT
            EncryptedJWT jwt = new EncryptedJWT(header, claimsSet);

            // Encrypt
            JWEEncrypter encrypter = new DirectEncrypter(secretKey);
            jwt.encrypt(encrypter);

            return jwt.serialize();

        } catch (JOSEException e) {
            throw new RuntimeException("Failed to encrypt token", e);
        }
    }

    /**
     * Record containing validated token claims.
     */
    public record TokenClaims(
            UUID userId,
            String email,
            Instant issuedAt,
            Instant expiresAt,
            String tokenId
    ) {}
}
