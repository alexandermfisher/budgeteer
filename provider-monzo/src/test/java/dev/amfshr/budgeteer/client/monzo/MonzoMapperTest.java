package dev.amfshr.budgeteer.client.monzo;

import dev.amfshr.budgeteer.bank.BankAccount;
import dev.amfshr.budgeteer.bank.BankTokens;
import dev.amfshr.budgeteer.bank.BankTransaction;
import dev.amfshr.budgeteer.client.monzo.dto.MonzoAccountResponse;
import dev.amfshr.budgeteer.client.monzo.dto.MonzoMerchantResponse;
import dev.amfshr.budgeteer.client.monzo.dto.MonzoTransactionResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MonzoMapper")
class MonzoMapperTest {

    @Nested
    @DisplayName("toBankTokens")
    class ToBankTokens {

        @Test
        @DisplayName("expires_in present → expiresAt ≈ now + n")
        void expiresInPresent() {
            Map<String, Object> response = Map.of("access_token", "tok", "expires_in", 3600);

            BankTokens tokens = MonzoMapper.toBankTokens(response);

            assertThat(tokens.accessToken()).isEqualTo("tok");
            assertThat(tokens.expiresAt())
                    .isAfter(Instant.now().plusSeconds(3500))
                    .isBefore(Instant.now().plusSeconds(3700));
        }

        @Test
        @DisplayName("expires_in null → expiresAt null")
        void expiresInNull() {
            Map<String, Object> response = Map.of("access_token", "tok");

            BankTokens tokens = MonzoMapper.toBankTokens(response);

            assertThat(tokens.expiresAt()).isNull();
        }

        @Test
        @DisplayName("refresh_token null → null")
        void refreshTokenNull() {
            Map<String, Object> response = Map.of("access_token", "tok", "expires_in", 3600);

            BankTokens tokens = MonzoMapper.toBankTokens(response);

            assertThat(tokens.refreshToken()).isNull();
        }
    }

    @Nested
    @DisplayName("toBankAccount")
    class ToBankAccount {

        @Test
        @DisplayName("normal account mapping")
        void normal() {
            MonzoAccountResponse ar = new MonzoAccountResponse(
                    "acc_001", "uk_retail", "Current", "GBP", false, "2024-01-01T00:00:00Z");

            BankAccount account = MonzoMapper.toBankAccount(ar, "{\"raw\":true}");

            assertThat(account.externalId()).isEqualTo("acc_001");
            assertThat(account.type()).isEqualTo("uk_retail");
            assertThat(account.description()).isEqualTo("Current");
            assertThat(account.currency()).isEqualTo("GBP");
            assertThat(account.closed()).isFalse();
            assertThat(account.createdAt()).isNotNull();
        }

        @Test
        @DisplayName("description null")
        void descriptionNull() {
            MonzoAccountResponse ar = new MonzoAccountResponse(
                    "acc_001", "uk_retail", null, "GBP", false, null);

            BankAccount account = MonzoMapper.toBankAccount(ar, null);

            assertThat(account.description()).isNull();
        }

        @Test
        @DisplayName("created null → createdAt null")
        void createdNull() {
            MonzoAccountResponse ar = new MonzoAccountResponse(
                    "acc_001", "uk_retail", null, "GBP", false, null);

            BankAccount account = MonzoMapper.toBankAccount(ar, null);

            assertThat(account.createdAt()).isNull();
        }

        @Test
        @DisplayName("created blank → createdAt null")
        void createdBlank() {
            MonzoAccountResponse ar = new MonzoAccountResponse(
                    "acc_001", "uk_retail", null, "GBP", false, "");

            BankAccount account = MonzoMapper.toBankAccount(ar, null);

            assertThat(account.createdAt()).isNull();
        }

        @Test
        @DisplayName("rawJson passthrough")
        void rawJsonPassthrough() {
            MonzoAccountResponse ar = new MonzoAccountResponse(
                    "acc_001", "uk_retail", null, "GBP", false, null);
            String raw = "{\"id\":\"acc_001\"}";

            BankAccount account = MonzoMapper.toBankAccount(ar, raw);

            assertThat(account.rawJson()).isEqualTo(raw);
        }
    }

    @Nested
    @DisplayName("toBankTransaction")
    class ToBankTransaction {

        @Test
        @DisplayName("settled null → settledAt null")
        void settledNull() {
            MonzoTransactionResponse tx = tx("tx_001", null, null);

            BankTransaction result = MonzoMapper.toBankTransaction(tx, null);

            assertThat(result.settledAt()).isNull();
        }

        @Test
        @DisplayName("settled blank → settledAt null")
        void settledBlank() {
            MonzoTransactionResponse tx = tx("tx_001", "", null);

            BankTransaction result = MonzoMapper.toBankTransaction(tx, null);

            assertThat(result.settledAt()).isNull();
        }

        @Test
        @DisplayName("decline_reason present → declined=true")
        void declineReasonPresent() {
            MonzoTransactionResponse tx = tx("tx_001", null, "insufficient_funds");

            BankTransaction result = MonzoMapper.toBankTransaction(tx, null);

            assertThat(result.declined()).isTrue();
        }

        @Test
        @DisplayName("decline_reason absent → declined=false")
        void declineReasonAbsent() {
            MonzoTransactionResponse tx = tx("tx_001", null, null);

            BankTransaction result = MonzoMapper.toBankTransaction(tx, null);

            assertThat(result.declined()).isFalse();
        }

        @Test
        @DisplayName("decline_reason blank → declined=false")
        void declineReasonBlank() {
            MonzoTransactionResponse tx = tx("tx_001", null, "");

            BankTransaction result = MonzoMapper.toBankTransaction(tx, null);

            assertThat(result.declined()).isFalse();
        }

        @Test
        @DisplayName("merchant null → null name/category")
        void merchantNull() {
            MonzoTransactionResponse tx = txNoMerchant("tx_001", null, null);

            BankTransaction result = MonzoMapper.toBankTransaction(tx, null);

            assertThat(result.merchantName()).isNull();
            assertThat(result.merchantCategory()).isNull();
        }

        @Test
        @DisplayName("notes null")
        void notesNull() {
            MonzoTransactionResponse tx = tx("tx_001", null, null);

            BankTransaction result = MonzoMapper.toBankTransaction(tx, null);

            assertThat(result.notes()).isNull();
        }

        @Test
        @DisplayName("rawJson passthrough")
        void rawJsonPassthrough() {
            MonzoTransactionResponse tx = tx("tx_001", null, null);
            String raw = "{\"id\":\"tx_001\"}";

            BankTransaction result = MonzoMapper.toBankTransaction(tx, raw);

            assertThat(result.rawJson()).isEqualTo(raw);
        }
    }

    private static MonzoTransactionResponse tx(String id, String settled, String declineReason) {
        return new MonzoTransactionResponse(
                id, -500, "GBP", "Test",
                new MonzoMerchantResponse("Shop", "shopping"),
                null, declineReason, "2024-06-01T10:00:00Z", settled);
    }

    private static MonzoTransactionResponse txNoMerchant(String id, String settled,
                                                         String declineReason) {
        return new MonzoTransactionResponse(
                id, -500, "GBP", "Test",
                null,
                null, declineReason, "2024-06-01T10:00:00Z", settled);
    }
}
