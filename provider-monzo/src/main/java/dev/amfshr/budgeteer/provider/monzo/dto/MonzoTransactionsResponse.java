package dev.amfshr.budgeteer.provider.monzo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record MonzoTransactionsResponse(
        List<MonzoTransactionResponse> transactions
) {
    public MonzoTransactionsResponse(@JsonProperty("transactions") List<MonzoTransactionResponse> transactions) {
        this.transactions = transactions;
    }
}
