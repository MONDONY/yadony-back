package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Tâche 15 — scheduler d'expiration mobile money.
 *
 * <p>Écart par rapport au cahier des charges (voir task-15-report.md) : le brief appelait
 * {@code BidRepository#findByStatusAndPaymentMethodAndAwaitingPaymentExpiresAtBefore} à 3
 * arguments (non bornée). Consigne explicite de la tâche : le lot doit être borné, sur le
 * modèle de {@code PawapayReconciliationPoller} (tâche 10). La méthode a donc gagné un 4e
 * paramètre {@code Pageable} — les stubs ci-dessous passent {@code any()} pour ce paramètre,
 * et {@link #expireUnpaidBids_boundsTheBatch} capture sa valeur réelle pour prouver le borné.
 */
@ExtendWith(MockitoExtension.class)
class MobileMoneyPaymentDeadlineSchedulerTest {

    @Mock BidRepository bidRepository;
    @Mock MobileMoneyBidPaymentService service;
    @InjectMocks MobileMoneyPaymentDeadlineScheduler scheduler;

    private static BidEntity bid() {
        BidEntity b = new BidEntity();
        ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
        b.setStatus(BidStatus.AWAITING_PAYMENT);
        b.setPaymentMethod(PaymentMethod.MOBILE_MONEY);
        return b;
    }

    @Test
    void expiresEachBid_independently() {
        BidEntity a = bid();
        BidEntity b = bid();
        when(bidRepository.findByStatusAndPaymentMethodAndAwaitingPaymentExpiresAtBefore(
                eq(BidStatus.AWAITING_PAYMENT), eq(PaymentMethod.MOBILE_MONEY), any(), any()))
                .thenReturn(List.of(a, b));
        doThrow(new IllegalStateException("boom")).when(service).expire(a.getId());

        scheduler.expireUnpaidBids();

        verify(service).expire(a.getId());
        verify(service).expire(b.getId());
    }

    /**
     * Preuve du borné : le scheduler ne demande jamais une liste illimitée, mais une page de
     * taille fixe {@link MobileMoneyPaymentDeadlineScheduler#BATCH_SIZE} — même motif, même
     * valeur que {@code PawapayReconciliationPoller} (tâche 10). Un incident qui laisserait
     * s'accumuler des centaines de bids en souffrance ne doit jamais faire durer un passage du
     * scheduler indéfiniment sur l'unique pool de scheduling partagé par tous les crons du dépôt.
     */
    @Test
    void expireUnpaidBids_boundsTheBatch() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        when(bidRepository.findByStatusAndPaymentMethodAndAwaitingPaymentExpiresAtBefore(
                eq(BidStatus.AWAITING_PAYMENT), eq(PaymentMethod.MOBILE_MONEY), any(), pageable.capture()))
                .thenReturn(List.of());

        scheduler.expireUnpaidBids();

        assertThat(pageable.getValue().getPageSize()).isEqualTo(MobileMoneyPaymentDeadlineScheduler.BATCH_SIZE);
        assertThat(pageable.getValue().getPageNumber()).isZero();
    }
}
