package dev.amfshr.budgeteer.client.monzo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MonzoBalanceResponse(
        long balance,
        long totalBalance,
        String currency,
        long spendToday
) {
    public MonzoBalanceResponse(
            @JsonProperty("balance") long balance,
            @JsonProperty("total_balance") long totalBalance,
            @JsonProperty("currency") String currency,
            @JsonProperty("spend_today") long spendToday
    ) {
        this.balance = balance;
        this.totalBalance = totalBalance;
        this.currency = currency;
        this.spendToday = spendToday;
    }
}
