package dev.amfshr.budgeteer.provider.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BankTransaction")
class BankTransactionTest {

    @Test
    @DisplayName("toString redacts rawJson")
    void toStringRedactsRawJson() {
        BankTransaction tx = new BankTransaction(
                "tx_001", -500, "GBP", "Coffee", "Starbucks", "eating_out",
                null, false, Instant.now(), null,
                "{\"local_amount\":-500,\"category\":\"eating_out\"}");

        String str = tx.toString();

        assertThat(str).doesNotContain("local_amount");
        assertThat(str).doesNotContain("\"category\"");
        assertThat(str).contains("<redacted>");
    }

    @Test
    @DisplayName("toString handles null rawJson")
    void toStringHandlesNullRawJson() {
        BankTransaction tx = new BankTransaction(
                "tx_001", -500, "GBP", "Coffee", null, null,
                null, false, Instant.now(), null, null);

        String str = tx.toString();

        assertThat(str).contains("rawJson=null");
    }
}
