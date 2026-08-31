package dev.amfshr.budgeteer.api.account.dto;

import java.util.UUID;

/**
 * Spending summary windows for one account, boundaries computed in {@code zone}.
 * {@code out} is a positive magnitude; {@code in} is ≥ 0.
 */
public record AccountSummaryResponse(
        UUID accountId, String zone,
        WindowSums today, WindowSums thisWeek, WindowSums monthToDate) {

    /** Signed-in / magnitude-out sums for one window. */
    public record WindowSums(long inMinorUnits, long outMinorUnits) {}
}
