package com.yadony.api.assistant.quota;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Double de test : même contrat que la base (comptage atomique, une ligne par utilisateur et par jour). */
public class InMemoryAssistantUserDailyUsageStore implements AssistantUserDailyUsageStore {

    private final Map<String, AtomicInteger> rows = new ConcurrentHashMap<>();

    @Override
    public int tryConsume(String uid, LocalDate day, int limit) {
        AtomicInteger row = rows.computeIfAbsent(uid + ":" + day, k -> new AtomicInteger());
        synchronized (row) {
            if (row.get() >= limit) return REFUSED;
            return row.incrementAndGet();
        }
    }

    public int questions(String uid, LocalDate day) {
        AtomicInteger row = rows.get(uid + ":" + day);
        return row == null ? 0 : row.get();
    }
}
