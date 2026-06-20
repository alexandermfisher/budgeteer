package dev.amf.budgeteer.api.monzo.dto;

import org.jspecify.annotations.Nullable;

import java.util.List;

public record MonzoSyncProgressResponse(
        List<AccountProgress> accounts
) {
    public record AccountProgress(
            String accountId,
            String description,
            String status,
            int progressPercent,
            @Nullable String currentWindowDate,
            long transactionCount
    ) {}
}
