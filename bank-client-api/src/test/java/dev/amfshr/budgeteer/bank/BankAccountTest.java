package dev.amfshr.budgeteer.bank;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BankAccount")
class BankAccountTest {

    @Test
    @DisplayName("toString redacts rawJson")
    void toStringRedactsRawJson() {
        BankAccount account = new BankAccount(
                "acc_001", "uk_retail", "Current", "GBP", false, null,
                "{\"account_number\":\"12345678\",\"sort_code\":\"040004\"}");

        String str = account.toString();

        assertThat(str).doesNotContain("12345678");
        assertThat(str).doesNotContain("040004");
        assertThat(str).contains("<redacted>");
    }

    @Test
    @DisplayName("toString handles null rawJson")
    void toStringHandlesNullRawJson() {
        BankAccount account = new BankAccount(
                "acc_001", "uk_retail", "Current", "GBP", false, null, null);

        String str = account.toString();

        assertThat(str).contains("rawJson=null");
    }
}
