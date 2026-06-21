package dev.amfshr.budgeteer.api.monzo.dto;

import dev.amfshr.budgeteer.service.monzo.MonzoConnectionService;

public record MonzoStatusResponse(
        boolean connected,
        long connectionCount,
        MonzoConnectionService.TokenStatus tokenStatus,
        MonzoConnectionService.BackfillStatus backfillStatus
) {
}
