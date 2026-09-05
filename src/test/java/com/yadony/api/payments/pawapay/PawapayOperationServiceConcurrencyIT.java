package com.yadony.api.payments.pawapay;

import static org.assertj.core.api.Assertions.assertThat;

import com.yadony.api.payments.pawapay.events.PawapayOperationCompletedEvent;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class PawapayOperationServiceConcurrencyIT {

    // Une @TestConfiguration imbriquée est elle-même un bean : pas de @Bean qui renverrait
    // `this`, ce qui créerait un second bean du même type et rendrait l'injection ambiguë.
    @TestConfiguration
    static class Counter {
        final AtomicInteger completed = new AtomicInteger();
        @EventListener void on(PawapayOperationCompletedEvent e) { completed.incrementAndGet(); }
    }

    @Autowired PawapayOperationService service;
    @Autowired PawapayOperationRepository repository;
    @Autowired Counter counter;

    @Test
    void twoConcurrentCompletedCallbacks_moveTheRowOnce_andPublishOnce() throws Exception {
        PawapayOperationEntity op = service.create(PawapayOperationKind.DEPOSIT, null, null,
                new BigDecimal("15000"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        int before = counter.completed.get();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Boolean> a = pool.submit(() -> { go.await(); return service.apply(op.getId(), PawapayOperationStatus.COMPLETED,
                null, null, "a", null, "{}", PawapayOperationService.Source.CALLBACK); });
        Future<Boolean> b = pool.submit(() -> { go.await(); return service.apply(op.getId(), PawapayOperationStatus.COMPLETED,
                null, null, "b", null, "{}", PawapayOperationService.Source.POLL); });
        go.countDown();
        boolean ra = a.get();
        boolean rb = b.get();
        pool.shutdown();

        assertThat(ra ^ rb).as("exactement un gagnant").isTrue();
        assertThat(counter.completed.get() - before).isEqualTo(1);
        assertThat(repository.findById(op.getId()).orElseThrow().getStatus()).isEqualTo(PawapayOperationStatus.COMPLETED);
    }
}
