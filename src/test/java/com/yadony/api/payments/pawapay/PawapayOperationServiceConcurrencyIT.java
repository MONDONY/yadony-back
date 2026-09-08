package com.yadony.api.payments.pawapay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yadony.api.payments.pawapay.dto.PawapayInitiationResult;
import com.yadony.api.payments.pawapay.events.PawapayOperationCompletedEvent;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class PawapayOperationServiceConcurrencyIT {

    // Une @TestConfiguration imbriquée est elle-même un bean : pas de @Bean qui renverrait
    // `this`, ce qui créerait un second bean du même type et rendrait l'injection ambiguë.
    @TestConfiguration
    static class Counter {
        final AtomicInteger completed = new AtomicInteger();

        // @EventListener simple (pas @TransactionalEventListener) : délibéré ICI pour
        // observer l'événement DANS la transaction d'apply(), au moment même où ce test
        // l'assère juste après l'appel synchrone qui l'a publié. Ce n'est PAS le motif à
        // copier en production sur un vrai listener de paiement : la règle projet impose
        // @TransactionalEventListener(phase = AFTER_COMMIT) + @Transactional(REQUIRES_NEW)
        // pour ne jamais agir sur une écriture pas encore committée (voir la Javadoc de
        // PawapayOperationCompletedEvent).
        @EventListener void on(PawapayOperationCompletedEvent e) { completed.incrementAndGet(); }
    }

    @Autowired PawapayOperationService service;
    @Autowired PawapayOperationRepository repository;
    @Autowired Counter counter;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void twoConcurrentCompletedCallbacks_moveTheRowOnce_andPublishOnce() throws Exception {
        PawapayOperationEntity op = service.create(PawapayOperationKind.DEPOSIT, null, null,
                new BigDecimal("15000"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        int before = counter.completed.get();
        // Revue ronde 1, point 8 : un simple CountDownLatch ne garantit qu'un départ groupé,
        // pas un chevauchement réel au moment de l'UPDATE — une exécution purement
        // séquentielle satisferait l'assertion à l'identique. La CyclicBarrier force les
        // deux threads à se synchroniser juste avant l'appel à apply(), immédiatement avant
        // la requête SQL, pour un vrai chevauchement contre la même ligne.
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<Boolean> a = pool.submit(() -> { barrier.await(); return service.apply(op.getId(), PawapayOperationStatus.COMPLETED,
                null, null, "a", null, "{}", PawapayOperationService.Source.CALLBACK); });
        Future<Boolean> b = pool.submit(() -> { barrier.await(); return service.apply(op.getId(), PawapayOperationStatus.COMPLETED,
                null, null, "b", null, "{}", PawapayOperationService.Source.POLL); });
        boolean ra = a.get();
        boolean rb = b.get();
        pool.shutdown();

        assertThat(ra ^ rb).as("exactement un gagnant").isTrue();
        assertThat(counter.completed.get() - before).isEqualTo(1);
        assertThat(repository.findById(op.getId()).orElseThrow().getStatus()).isEqualTo(PawapayOperationStatus.COMPLETED);
    }

    @Test
    void markSubmitted_afterCallbackAlreadyCompleted_doesNotResurrectStatus_noDuplicateEvent() {
        // Revue ronde 1, point 1 (CRITIQUE) : reproduit le désastre décrit en revue — le
        // callback pawaPay arrive et complète l'opération AVANT que la réponse HTTP de
        // notre propre appel d'initiation ne revienne (hypothèse déjà faite par la
        // conception : l'id est persisté avant l'appel HTTP précisément pour ce genre de
        // course). Sans le UPDATE gardé par WHERE status = CREATED, ce markSubmitted
        // tardif écraserait silencieusement COMPLETED par ACCEPTED : applyTransition,
        // bulk JPQL, n'incrémente jamais @Version, donc aucune OptimisticLockException ne
        // protège cette paire précise.
        PawapayOperationEntity op = service.create(PawapayOperationKind.DEPOSIT, null, null,
                new BigDecimal("15000"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        int before = counter.completed.get();

        boolean callbackWon = service.apply(op.getId(), PawapayOperationStatus.COMPLETED,
                null, null, "ptx-callback", null, "{}", PawapayOperationService.Source.CALLBACK);
        assertThat(callbackWon).isTrue();
        assertThat(counter.completed.get() - before).isEqualTo(1);

        // La réponse HTTP (tardive) tente maintenant de marquer l'opération soumise.
        service.markSubmitted(op.getId(), PawapayInitiationResult.accepted());

        assertThat(repository.findById(op.getId()).orElseThrow().getStatus())
                .as("markSubmitted ne doit jamais faire régresser un statut final")
                .isEqualTo(PawapayOperationStatus.COMPLETED);

        // Conséquence directe si la régression revenait : un poller qui rejouerait
        // COMPLETED sur une ligne ressuscitée en ACCEPTED republierait l'événement — un
        // second versement pour le même paiement.
        boolean replay = service.apply(op.getId(), PawapayOperationStatus.COMPLETED,
                null, null, "ptx-poll", null, "{}", PawapayOperationService.Source.POLL);
        assertThat(replay).isFalse();
        assertThat(counter.completed.get() - before)
                .as("aucun second versement pour le même paiement")
                .isEqualTo(1);
    }

    @Test
    void create_commitsInItsOwnTransaction_evenWhenCallerRollsBack() {
        // Revue ronde 1, point 3 : create() doit survivre au rollback de son appelant —
        // c'est la pièce maîtresse qui garantit qu'une panne réseau APRÈS l'insertion ne
        // fait pas disparaître la ligne alors que pawaPay a peut-être déjà accepté le
        // dépôt (l'expéditeur retenterait alors avec un nouvel id : double débit).
        UUID paymentId = UUID.randomUUID();
        UUID[] createdId = new UUID[1];

        TransactionTemplate callerTx = new TransactionTemplate(txManager);
        assertThatThrownBy(() -> callerTx.execute(status -> {
            PawapayOperationEntity created = service.create(PawapayOperationKind.DEPOSIT, paymentId, null,
                    new BigDecimal("15000"), "XOF", "ORANGE_SEN", "SN", "221771234567");
            createdId[0] = created.getId();
            throw new IllegalStateException("panne simulée après la création, avant l'appel HTTP");
        })).isInstanceOf(IllegalStateException.class);

        TransactionTemplate readTx = new TransactionTemplate(txManager);
        Boolean stillThere = readTx.execute(status -> repository.findById(createdId[0]).isPresent());
        assertThat(stillThere).as("create() (REQUIRES_NEW) doit survivre au rollback de l'appelant").isTrue();
    }
}
