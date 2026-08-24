package dev.amfshr.budgeteer.client.monzo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

public record MonzoWhoAmIResponse(
        boolean authenticated,
        @Nullable String userId
) {
    public MonzoWhoAmIResponse(
            @JsonProperty("authenticated") boolean authenticated,
            @JsonProperty("user_id") @Nullable String userId
    ) {
        this.authenticated = authenticated;
        this.userId = userId;
    }
}
