package com.yadony.api.cancellation;

import com.yadony.api.cancellation.dto.AdminBidCancelRequest;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Annulation admin face à un versement ou une livraison concurrents (base réelle) : verrou du
 * colis puis du paiement, statuts relus sous verrou. Jamais « annulé + remboursé » alors que le
 * voyageur est payé, ni « livré » sur un colis annulé.
 */
@SpringBootTest
@ActiveProfiles("test")
class AdminBidCancellationConcurrencyIT {

    @Autowired AdminBidCancellationService service;
    @Autowired BidRepository bidRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> bids = new ArrayList<>();
    private final List<UUID> payments = new ArrayList<>();
    private static final AdminBidCancelRequest REQUEST =
            new AdminBidCancelRequest(AdminBidCancelReason.SENDER_REQUEST, "Demande écrite au support");

    @AfterEach
    void cleanUp() {
        payments.forEach(id -> jdbc.update("DELETE FROM payments WHERE id = ?", id));
        bids.forEach(id -> jdbc.update("DELETE FROM bids WHERE id = ?", id));
    }

    private TransactionTemplate tx() {
        TransactionTemplate t = new TransactionTemplate(transactionManager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t;
    }

    private BidEntity bid(BidStatus status) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(UUID.randomUUID());
        b.setSenderId(UUID.randomUUID());
        b.setStatus(status);
        b = bidRepository.saveAndFlush(b);
        bids.add(b.getId());
        return b;
    }

    private PaymentEntity escrow(BidEntity bid) {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(bid.getId());
        p.setRail(PaymentRail.STRIPE);
        p.setStripePaymentIntentId("pi_" + UUID.randomUUID());
        p.setAmount(new BigDecimal("40.00"));
        p.setCommissionAmount(new BigDecimal("4.80"));
        p.setCurrency("EUR");
        p.setStatus(PaymentStatus.ESCROW);
        p = paymentRepository.saveAndFlush(p);
        payments.add(p.getId());
        return p;
    }

    @Test
    void versementEnCours_annulationAttendPuisRefuse_payementVerse() throws Exception {
        BidEntity b = bid(BidStatus.HANDED_OVER);
        PaymentEntity p = escrow(b);
        CountDownLatch claimed = new CountDownLatch(1);

        // Versement concurrent (force-release ou job) : claim ESCROW → RELEASED, commit 400 ms plus tard.
        CompletableFuture<Void> release = CompletableFuture.runAsync(() -> tx().executeWithoutResult(s -> {
            assertThat(paymentRepository.markReleasedIfEscrow(p.getId(), LocalDateTime.now())).isEqualTo(1);
            claimed.countDown();
            sleep(400);
        }));
        assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> service.cancel(b.getId(), UUID.randomUUID(), REQUEST))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("payment-released"));
        release.get(10, TimeUnit.SECONDS);

        assertThat(bidRepository.findById(b.getId()).orElseThrow().getStatus()).isEqualTo(BidStatus.HANDED_OVER);
        assertThat(paymentRepository.findStatusById(p.getId())).contains(PaymentStatus.RELEASED);
    }

    @Test
    void livraisonEnCours_annulationAttendPuisRefuse() throws Exception {
        BidEntity b = bid(BidStatus.ARRIVED);
        CountDownLatch locked = new CountDownLatch(1);

        // Confirmation de livraison : verrou du colis (findByIdForUpdate), COMPLETED, commit plus tard.
        CompletableFuture<Void> delivery = CompletableFuture.runAsync(() -> tx().executeWithoutResult(s -> {
            bidRepository.lockForUpdate(b.getId());
            BidEntity mine = bidRepository.findById(b.getId()).orElseThrow();
            locked.countDown();
            sleep(400);
            mine.setStatus(BidStatus.COMPLETED);
            bidRepository.save(mine);
        }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> service.cancel(b.getId(), UUID.randomUUID(), REQUEST))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("bid-delivered"));
        delivery.get(10, TimeUnit.SECONDS);
        assertThat(bidRepository.findById(b.getId()).orElseThrow().getStatus()).isEqualTo(BidStatus.COMPLETED);
    }

    @Test
    void annulationEnCours_livraisonAttendPuisVoitAnnule() throws Exception {
        BidEntity b = bid(BidStatus.ARRIVED);
        CountDownLatch cancelled = new CountDownLatch(1);

        CompletableFuture<Void> cancel = CompletableFuture.runAsync(() -> tx().executeWithoutResult(s -> {
            service.cancel(b.getId(), UUID.randomUUID(), REQUEST);
            cancelled.countDown();
            sleep(400);
        }));
        assertThat(cancelled.await(10, TimeUnit.SECONDS)).isTrue();

        // Première étape de la livraison (et de la libération forcée) : relecture sous verrou.
        BidStatus seenByDelivery = tx().execute(s -> {
            bidRepository.lockForUpdate(b.getId());
            return bidRepository.findById(b.getId()).orElseThrow().getStatus();
        });
        cancel.get(10, TimeUnit.SECONDS);

        assertThat(seenByDelivery).isEqualTo(BidStatus.CANCELLED);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
