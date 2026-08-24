package dev.amfshr.budgeteer.provider.monzo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record MonzoAccountsResponse(
        List<MonzoAccountResponse> accounts
) {
    public MonzoAccountsResponse(@JsonProperty("accounts") List<MonzoAccountResponse> accounts) {
        this.accounts = accounts;
    }
}
