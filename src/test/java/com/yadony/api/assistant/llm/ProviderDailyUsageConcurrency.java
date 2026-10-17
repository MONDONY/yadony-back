package com.yadony.api.assistant.llm;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Lance des appels au même instant depuis plusieurs fils, pour éprouver l'atomicité des compteurs. */
final class ProviderDailyUsageConcurrency {

    private ProviderDailyUsageConcurrency() {
    }

    static int reserveConcurrently(ProviderDailyUsageStore store, UUID id, LocalDate day, int limit,
                                   int threads, int perThread) throws Exception {
        CountDownLatch depart = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                results.add(pool.submit(() -> {
                    depart.await();
                    int ok = 0;
                    for (int i = 0; i < perThread; i++) {
                        if (store.tryReserve(id, day, limit)) ok++;
                    }
                    return ok;
                }));
            }
            depart.countDown();
            int total = 0;
            for (Future<Integer> f : results) total += f.get();
            return total;
        }
    }

    static void runConcurrently(int threads, int perThread, Runnable action) throws Exception {
        CountDownLatch depart = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                results.add(pool.submit(() -> {
                    depart.await();
                    for (int i = 0; i < perThread; i++) action.run();
                    return null;
                }));
            }
            depart.countDown();
            for (Future<?> f : results) f.get();
        }
    }
}
