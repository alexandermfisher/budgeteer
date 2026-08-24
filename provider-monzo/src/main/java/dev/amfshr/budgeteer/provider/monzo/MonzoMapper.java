package dev.amfshr.budgeteer.provider.monzo;

import dev.amfshr.budgeteer.provider.model.BankAccount;
import dev.amfshr.budgeteer.provider.model.BankTokens;
import dev.amfshr.budgeteer.provider.model.BankTransaction;
import dev.amfshr.budgeteer.provider.monzo.dto.MonzoAccountResponse;
import dev.amfshr.budgeteer.provider.monzo.dto.MonzoTransactionResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;

/**
 * Package-private mapper from Monzo JSON DTOs to provider-neutral bank types.
 */
final class MonzoMapper {

    private static final Logger log = LoggerFactory.getLogger(MonzoMapper.class);

    private MonzoMapper() {
    }

    /**
     * Map a raw Monzo token response map to {@link BankTokens}.
     */
    static BankTokens toBankTokens(Map<String, Object> response) {
        String accessToken = (String) response.get("access_token");
        String refreshToken = (String) response.get("refresh_token");
        Integer expiresIn = (Integer) response.get("expires_in");

        return new BankTokens(
                accessToken,
                refreshToken,
                expiresIn != null ? Instant.now().plusSeconds(expiresIn) : null
        );
    }

    /**
     * Map a Monzo account response to a {@link BankAccount}.
     */
    static BankAccount toBankAccount(MonzoAccountResponse ar, @Nullable String rawJson) {
        Instant createdAt = parseInstant(ar.created());
        return new BankAccount(
                ar.id(),
                ar.type(),
                ar.description(),
                ar.currency(),
                ar.closed(),
                createdAt,
                rawJson
        );
    }

    /**
     * Map a Monzo transaction response to a {@link BankTransaction}.
     */
    static BankTransaction toBankTransaction(MonzoTransactionResponse tx, @Nullable String rawJson) {
        Instant settledAt = (tx.settled() != null && !tx.settled().isBlank())
                ? Instant.parse(tx.settled())
                : null;
        Instant createdAt = (tx.created() != null && !tx.created().isBlank())
                ? Instant.parse(tx.created())
                : Instant.now();

        boolean declined = tx.declineReason() != null && !tx.declineReason().isBlank();

        return new BankTransaction(
                tx.id(),
                tx.amount(),
                tx.currency(),
                tx.description(),
                tx.merchant() != null ? tx.merchant().name() : null,
                tx.merchant() != null ? tx.merchant().category() : null,
                tx.notes(),
                declined,
                createdAt,
                settledAt,
                rawJson
        );
    }

    @Nullable
    private static Instant parseInstant(@Nullable String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            log.debug("Could not parse Instant from: {}", s);
            return null;
        }
    }
}
