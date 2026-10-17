package com.yadony.api.assistant.quota;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Lance des questions au même instant depuis plusieurs fils, pour éprouver l'atomicité du compteur. */
final class AssistantUserDailyUsageConcurrency {

    private AssistantUserDailyUsageConcurrency() {
    }

    /** @return les comptes rendus pour chaque question accordée (les refus ne sont pas listés) */
    static List<Integer> consumeConcurrently(AssistantUserDailyUsageStore store, String uid, LocalDate day,
                                             int limit, int threads, int perThread) throws Exception {
        CountDownLatch depart = new CountDownLatch(1);
        List<Future<List<Integer>>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                results.add(pool.submit(() -> {
                    depart.await();
                    List<Integer> granted = new ArrayList<>();
                    for (int i = 0; i < perThread; i++) {
                        int used = store.tryConsume(uid, day, limit);
                        if (used != AssistantUserDailyUsageStore.REFUSED) granted.add(used);
                    }
                    return granted;
                }));
            }
            depart.countDown();
            List<Integer> all = new ArrayList<>();
            for (Future<List<Integer>> f : results) all.addAll(f.get());
            return all;
        }
    }
}
