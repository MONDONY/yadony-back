package com.yadony.api.payments;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import com.stripe.model.Transfer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Un colis livré dont le paiement n'a jamais atteint le séquestre (PENDING) était ignoré en silence :
 * ni versement, ni alerte, l'autorisation carte expirait à J+7 et le voyageur n'était pas payé
 * (sonde INV-08, paiement 500391e5). Un admin doit en être prévenu. Les autres statuts non-ESCROW
 * (déjà remboursé, versé, annulé) restent un cas normal, sans alerte.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryEventListenerPendingPaymentTest {

    /** Capture déjà faite par défaut : PaymentIntent succeeded, aucun charge id renvoyé. */
    private final com.yadony.api.payments.EscrowCaptureService escrowCapture = org.mockito.Mockito.mock(com.yadony.api.payments.EscrowCaptureService.class, invocation -> new com.yadony.api.payments.EscrowCaptureService.Outcome(null, false));

    @Mock com.yadony.api.payments.hold.PayoutHoldPolicy holdPolicy;
    @Mock com.yadony.api.admin.AdminAlertEscalator alertEscalator;
    @Mock private PaymentRepository paymentRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private BidRepository bidRepository;
    @Mock private AdminAlertService adminAlert;
    @Mock private com.yadony.api.voucher.CommissionVoucherService voucherService;

    private DeliveryEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new DeliveryEventListener(paymentRepository, userRepository,
                auditService, eventPublisher, bidRepository, adminAlert, voucherService, null, holdPolicy, alertEscalator,
                escrowCapture, org.mockito.Mockito.mock(com.yadony.api.payments.StripeTransferLookup.class), org.mockito.Mockito.mock(com.yadony.api.disputes.DisputeRepository.class));
    }

    private static void setId(Object entity, UUID id) throws Exception {
        Field field = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    private PaymentEntity payment(PaymentStatus status, UUID bidId) throws Exception {
        PaymentEntity payment = new PaymentEntity();
        setId(payment, UUID.randomUUID());
        payment.setBidId(bidId);
        payment.setStripePaymentIntentId("pi_x");
        payment.setAmount(BigDecimal.valueOf(83.60));
        payment.setStatus(status);
        return payment;
    }

    @Test
    void paiement_PENDING_a_la_livraison_leve_une_alerte_et_ne_verse_rien() throws Exception {
        UUID bidId = UUID.randomUUID();
        PaymentEntity payment = payment(PaymentStatus.PENDING, bidId);
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(payment));

        try (MockedStatic<Transfer> transfer = mockStatic(Transfer.class)) {
            listener.handleDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), UUID.randomUUID()));

            transfer.verifyNoInteractions();
        }
        verify(auditService).log(eq("PAYMENT"), eq(payment.getId()), eq("DELIVERY_PAYMENT_NOT_IN_ESCROW"),
                eq(bidId), any());
        verify(alertEscalator).raiseOnce(eq("DELIVERY_NOT_ESCROW_" + payment.getId()), anyString(), any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void paiement_deja_rembourse_ne_leve_pas_d_alerte() throws Exception {
        for (PaymentStatus normal : new PaymentStatus[]{
                PaymentStatus.REFUNDED, PaymentStatus.RELEASED, PaymentStatus.CANCELLED}) {
            UUID bidId = UUID.randomUUID();
            when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(payment(normal, bidId)));

            listener.handleDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), UUID.randomUUID()));
        }
        verifyNoInteractions(alertEscalator);
        verifyNoInteractions(auditService);
    }

    @Test
    void paiement_de_negociation_PENDING_repere_par_le_fil_leve_aussi_l_alerte() throws Exception {
        UUID bidId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        com.yadony.api.matching.BidEntity bid = new com.yadony.api.matching.BidEntity();
        bid.setLinkedNegotiationThreadId(threadId);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());
        PaymentEntity payment = payment(PaymentStatus.PENDING, null);
        when(paymentRepository.findByNegotiationThreadId(threadId)).thenReturn(Optional.of(payment));

        listener.handleDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), UUID.randomUUID()));

        verify(alertEscalator).raiseOnce(eq("DELIVERY_NOT_ESCROW_" + payment.getId()), anyString(), any());
    }

    @Test
    void rattrapage_d_un_paiement_toujours_PENDING_ne_verse_rien_et_garde_l_alerte() throws Exception {
        UUID bidId = UUID.randomUUID();
        PaymentEntity payment = payment(PaymentStatus.PENDING, bidId);
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(payment));

        EscrowReleaseOutcome outcome = listener.releaseAfterLateEscrow(bidId, UUID.randomUUID(), UUID.randomUUID(),
                DeliveryEventListener.SOURCE_LATE_ESCROW);

        assertThat(outcome).isEqualTo(EscrowReleaseOutcome.NOT_IN_ESCROW);
        verify(alertEscalator, never()).resolveOpen(anyString());
    }

    @Test
    void rattrapage_verse_et_clot_l_alerte_meme_si_la_cloture_echoue() throws Exception {
        UUID bidId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        com.yadony.api.matching.BidEntity bid = new com.yadony.api.matching.BidEntity();
        bid.setLinkedNegotiationThreadId(threadId);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());
        PaymentEntity payment = payment(PaymentStatus.ESCROW, null);
        payment.setCommissionAmount(BigDecimal.valueOf(3.60));
        payment.setCurrency("EUR");
        when(paymentRepository.findByNegotiationThreadId(threadId)).thenReturn(Optional.of(payment));
        when(paymentRepository.markReleasedIfEscrowAndUnguarded(eq(payment.getId()), any())).thenReturn(1);
        com.yadony.api.auth.UserEntity traveler = new com.yadony.api.auth.UserEntity();
        traveler.setStripeAccountId("acct_t");
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        when(alertEscalator.resolveOpen("DELIVERY_NOT_ESCROW_" + payment.getId()))
                .thenThrow(new IllegalStateException("base indisponible"));

        EscrowReleaseOutcome outcome;
        try (MockedStatic<Transfer> transfer = mockStatic(Transfer.class)) {
            transfer.when(() -> Transfer.create(any(com.stripe.param.TransferCreateParams.class),
                    any(com.stripe.net.RequestOptions.class))).thenReturn(org.mockito.Mockito.mock(Transfer.class));
            outcome = listener.releaseAfterLateEscrow(bidId, UUID.randomUUID(), travelerId,
                    DeliveryEventListener.SOURCE_LATE_ESCROW);
        }

        assertThat(outcome).isEqualTo(EscrowReleaseOutcome.RELEASED);
        verify(alertEscalator).resolveOpen("DELIVERY_NOT_ESCROW_" + payment.getId());
        verify(auditService).log(eq("PAYMENT"), eq(payment.getId()), eq("ESCROW_RELEASED_TRANSFER"), eq(bidId),
                org.mockito.ArgumentMatchers.argThat(m -> "late-escrow".equals(m.get("source"))));
    }
}
