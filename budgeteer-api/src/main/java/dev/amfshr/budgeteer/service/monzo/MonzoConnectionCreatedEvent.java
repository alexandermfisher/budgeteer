package dev.amfshr.budgeteer.service.monzo;

import java.util.UUID;

public record MonzoConnectionCreatedEvent(UUID connectionId, UUID userId) {
}
