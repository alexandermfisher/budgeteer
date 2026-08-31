package dev.amfshr.budgeteer.provider.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Sourced")
class SourcedTest {

    private static final BankAccount ACCOUNT = new BankAccount(
            "acc_001", "uk_retail", "Current", "GBP", false, null);

    @Test
    @DisplayName("toString redacts rawJson")
    void toStringRedactsRawJson() {
        Sourced<BankAccount> sourced = new Sourced<>(
                ACCOUNT, "{\"account_number\":\"12345678\",\"sort_code\":\"040004\"}");

        String str = sourced.toString();

        assertThat(str).doesNotContain("12345678");
        assertThat(str).doesNotContain("040004");
        assertThat(str).contains("<redacted>");
    }

    @Test
    @DisplayName("toString handles null rawJson")
    void toStringHandlesNullRawJson() {
        Sourced<BankAccount> sourced = new Sourced<>(ACCOUNT, null);

        assertThat(sourced.toString()).contains("rawJson=null");
    }

    @Test
    @DisplayName("null payload throws")
    void nullPayloadThrows() {
        assertThatThrownBy(() -> new Sourced<>(null, "{}"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("payload");
    }

    @Test
    @DisplayName("map transforms the payload and keeps the provenance")
    void mapKeepsProvenance() {
        String raw = "{\"id\":\"tx_001\"}";
        BankTransaction tx = new BankTransaction(
                "tx_001", -500, "GBP", "Coffee", null, null, null, false,
                Instant.parse("2024-06-01T10:00:00Z"), null);

        Sourced<String> mapped = new Sourced<>(tx, raw).map(BankTransaction::externalId);

        assertThat(mapped.payload()).isEqualTo("tx_001");
        assertThat(mapped.rawJson()).isEqualTo(raw);
    }

    @Test
    @DisplayName("map accepts a broader-input, narrower-output function (PECS bounds)")
    void mapAcceptsWidenedFunction() {
        java.util.function.Function<Object, Integer> hash = Object::hashCode;

        Sourced<Number> mapped = new Sourced<>(ACCOUNT, null).map(hash);

        assertThat(mapped.payload()).isEqualTo(ACCOUNT.hashCode());
    }
}
