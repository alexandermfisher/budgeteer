package dev.amf.budgeteer.api.monzo.dto;

import dev.amf.budgeteer.service.monzo.MonzoConnectionService;

public record MonzoStatusResponse(
        boolean connected,
        long connectionCount,
        MonzoConnectionService.TokenStatus tokenStatus
) {
}
