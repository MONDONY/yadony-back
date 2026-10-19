package com.yadony.api.payments.supplement;

import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.StripeReleaseIntentEntity;
import com.yadony.api.payments.StripeReleaseIntentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.yadony.api.payments.supplement.BidSupplementPricingTest.setId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Filet carte : remboursements en suspens, paiements tardifs, versements bloqués ou manqués. */
@ExtendWith(MockitoExtension.class)
class BidSupplementCardReconciliationJobTest {

    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    @Mock BidSupplementRepository supplements;
    @Mock BidSupplementCardPayouts payouts;
    @Mock BidSupplementCardRefunds refunds;
    @Mock PaymentRepository payments;
    @Mock StripeReleaseIntentRepository releaseIntents;

    private final BidSupplementProperties properties = new BidSupplementProperties();

    private BidSupplementCardReconciliationJob job() {
        return new BidSupplementCardReconciliationJob(supplements, payouts, refunds, payments, releaseIntents,
                new BidSupplementWindow(properties, Clock.fixed(NOW, ZoneOffset.UTC)), properties);
    }

    @Test
    void remboursementsEnSuspensEtPaiementsTardifs_relances_unEchecNArretePasLeLot() {
        UUID pending = UUID.randomUUID();
        UUID failing = UUID.randomUUID();
        UUID late = UUID.randomUUID();
        org.mockito.Mockito.lenient().when(supplements.findIdsByRailAndStatusInUpdatedBefore(eq(BidSupplementRail.STRIPE),
                eq(EnumSet.of(BidSupplementStatus.REFUND_PENDING)), eq(NOW_UTC.minusMinutes(10)), any(Pageable.class)))
                .thenReturn(List.of(failing, pending));
        when(supplements.findIdsPaidLateUnrefunded(eq(BidSupplementRail.STRIPE),
                eq(BidSupplementCardLedger.CLOSED_UNPAID), eq(NOW_UTC.minusMinutes(10)), any(Pageable.class)))
                .thenReturn(List.of(late));
        when(refunds.refund(failing)).thenThrow(new IllegalStateException("verrou"));
        when(refunds.refund(pending)).thenReturn(true);
        when(refunds.refund(late)).thenReturn(true);

        assertThat(job().run().refunded()).isEqualTo(2);
    }

    @Test
    void versementBloque_repris_etVersementManque_seulementSiLePrincipalEstVerse() {
        UUID stuck = UUID.randomUUID();
        UUID released = UUID.randomUUID();
        UUID principalHeld = UUID.randomUUID();
        UUID noPayment = UUID.randomUUID();
        org.mockito.Mockito.lenient().when(supplements.findIdsByRailAndStatusInUpdatedBefore(eq(BidSupplementRail.STRIPE),
                eq(EnumSet.of(BidSupplementStatus.PAYOUT_PENDING)), any(), any(Pageable.class)))
                .thenReturn(List.of(stuck));
        when(payouts.resume(stuck)).thenReturn(true);
        when(supplements.findIdsByRailAndStatusPaidBefore(BidSupplementRail.STRIPE, BidSupplementStatus.PAID,
                NOW_UTC.minusMinutes(30), org.springframework.data.domain.PageRequest.of(0, 100)))
                .thenReturn(List.of(released, principalHeld, noPayment));
        mockPrincipal(released, PaymentStatus.RELEASED, StripeReleaseIntentEntity.Status.DONE);
        mockPrincipal(principalHeld, PaymentStatus.RELEASED, StripeReleaseIntentEntity.Status.PENDING);
        when(supplements.findBidIdById(noPayment)).thenReturn(Optional.empty());
        when(payouts.payout(released)).thenReturn(true);

        assertThat(job().run().paidOut()).isEqualTo(2);
        verify(payouts, never()).payout(principalHeld);
        verify(payouts, never()).payout(noPayment);
    }

    @Test
    void partageAdmin_principalNonVerseParLExecuteur_supplementLaisseAPaye() {
        UUID split = UUID.randomUUID();
        when(supplements.findIdsByRailAndStatusPaidBefore(any(), any(), any(), any())).thenReturn(List.of(split));
        mockPrincipal(split, PaymentStatus.REFUNDED, null);

        assertThat(job().run().paidOut()).isZero();
        verify(payouts, never()).payout(split);
    }

    @Test
    void filetCoupe_rienNeTourne() {
        properties.setReconcileEnabled(false);
        job().scheduledRun();
        verifyNoInteractions(supplements, payouts, refunds);

        properties.setReconcileEnabled(true);
        job().scheduledRun();
        verify(supplements).findIdsPaidLateUnrefunded(any(), any(), any(), any());
    }

    private void mockPrincipal(UUID supplementId, PaymentStatus status, StripeReleaseIntentEntity.Status intent) {
        UUID bidId = UUID.randomUUID();
        PaymentEntity payment = new PaymentEntity();
        setId(payment, UUID.randomUUID());
        payment.setStatus(status);
        when(supplements.findBidIdById(supplementId)).thenReturn(Optional.of(bidId));
        when(payments.findForBid(bidId)).thenReturn(Optional.of(payment));
        if (intent != null) {
            StripeReleaseIntentEntity release = new StripeReleaseIntentEntity();
            release.setStatus(intent);
            when(releaseIntents.findByPaymentId(payment.getId())).thenReturn(Optional.of(release));
        }
    }
}
