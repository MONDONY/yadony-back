package com.yadony.api.assistant.llm;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Compteurs du jour (UTC) des fournisseurs, persistés : ils survivent aux redémarrages. */
public interface ProviderDailyUsageStore {

    /** Pas de limite configurée : la réservation réussit toujours. */
    int NO_LIMIT = Integer.MAX_VALUE;

    /**
     * Réserve un essai pour ce jour, de façon atomique : sous concurrence, jamais plus de {@code limit}
     * réservations accordées.
     */
    boolean tryReserve(UUID providerId, LocalDate day, int limit);

    void addSuccess(UUID providerId, LocalDate day);

    /** Compteurs du jour des fournisseurs demandés, en une lecture ; un fournisseur absent n'a rien fait. */
    Map<UUID, DailyCount> read(Collection<UUID> providerIds, LocalDate day);

    record DailyCount(UUID providerId, int requests, int successes) {
    }
}
