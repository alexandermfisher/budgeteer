package dev.amfshr.budgeteer.provider.monzo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

public record MonzoTransactionResponse(
        String id,
        int amount,
        String currency,
        @Nullable String description,
        @Nullable MonzoMerchantResponse merchant,
        @Nullable String notes,
        @Nullable String declineReason,
        String created,
        @Nullable String settled
) {
    public MonzoTransactionResponse(
            @JsonProperty("id") String id,
            @JsonProperty("amount") int amount,
            @JsonProperty("currency") String currency,
            @JsonProperty("description") @Nullable String description,
            @JsonProperty("merchant") @Nullable MonzoMerchantResponse merchant,
            @JsonProperty("notes") @Nullable String notes,
            @JsonProperty("decline_reason") @Nullable String declineReason,
            @JsonProperty("created") String created,
            @JsonProperty("settled") @Nullable String settled
    ) {
        this.id = id;
        this.amount = amount;
        this.currency = currency;
        this.description = description;
        this.merchant = merchant;
        this.notes = notes;
        this.declineReason = declineReason;
        this.created = created;
        this.settled = settled;
    }
}
