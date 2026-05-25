package dev.amf.budgeteer.client.monzo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

public record MonzoMerchantResponse(
        @Nullable String name,
        @Nullable String category
) {
    public MonzoMerchantResponse(
            @JsonProperty("name") @Nullable String name,
            @JsonProperty("category") @Nullable String category
    ) {
        this.name = name;
        this.category = category;
    }
}
