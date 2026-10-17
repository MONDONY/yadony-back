package com.yadony.api.assistant.llm;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Double de test : même contrat que la base (réservation atomique, une ligne par fournisseur et jour). */
class InMemoryProviderDailyUsageStore implements ProviderDailyUsageStore {

    private final Map<String, int[]> rows = new ConcurrentHashMap<>();
    int reads;

    private int[] row(UUID id, LocalDate day) {
        return rows.computeIfAbsent(id + ":" + day, k -> new int[2]);
    }

    @Override
    public boolean tryReserve(UUID providerId, LocalDate day, int limit) {
        int[] r = row(providerId, day);
        synchronized (r) {
            if (r[0] >= limit) return false;
            r[0]++;
            return true;
        }
    }

    @Override
    public void addSuccess(UUID providerId, LocalDate day) {
        int[] r = row(providerId, day);
        synchronized (r) {
            r[1]++;
        }
    }

    @Override
    public Map<UUID, DailyCount> read(Collection<UUID> providerIds, LocalDate day) {
        reads++;
        Map<UUID, DailyCount> out = new HashMap<>();
        for (UUID id : providerIds) {
            int[] r = rows.get(id + ":" + day);
            if (r != null) {
                synchronized (r) {
                    out.put(id, new DailyCount(id, r[0], r[1]));
                }
            }
        }
        return out;
    }
}
