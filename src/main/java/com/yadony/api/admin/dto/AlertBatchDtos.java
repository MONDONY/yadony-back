package com.yadony.api.admin.dto;

import java.util.List;
import java.util.UUID;

/** Corps et réponses des gestes de masse et de report sur les alertes. */
public final class AlertBatchDtos {

    private AlertBatchDtos() {}

    /** Résolution de plusieurs alertes avec une même note. */
    public record ResolveBatchRequest(List<UUID> ids, String note) {}

    /** Ce qui s'est passé pour chaque identifiant demandé. */
    public record ResolveBatchResponse(List<UUID> resolved, List<UUID> alreadyResolved, List<UUID> notFound) {}

    public record ReopenAlertRequest(String note) {}

    /** Report d'une alerte : {@code hours} entre 1 et 168 (7 jours). */
    public record SnoozeAlertRequest(Integer hours, String note) {}
}
