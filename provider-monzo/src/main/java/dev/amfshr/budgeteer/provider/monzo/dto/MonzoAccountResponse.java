package dev.amfshr.budgeteer.provider.monzo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

public record MonzoAccountResponse(
        String id,
        String type,
        @Nullable String description,
        String currency,
        boolean closed,
        @Nullable String created
) {
    public MonzoAccountResponse(
            @JsonProperty("id") String id,
            @JsonProperty("type") String type,
            @JsonProperty("description") @Nullable String description,
            @JsonProperty("currency") String currency,
            @JsonProperty("closed") boolean closed,
            @JsonProperty("created") @Nullable String created
    ) {
        this.id = id;
        this.type = type;
        this.description = description;
        this.currency = currency;
        this.closed = closed;
        this.created = created;
    }
}
