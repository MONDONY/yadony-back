package com.yadony.api.payments;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.events.PaymentReleasedEvent;
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
 * Un remboursement partiel (charge.refunded non total) laisse le paiement en ESCROW. Le
 * versement calculait ensuite le net sur le montant TOTAL, sans retirer le remboursé : le
 * voyageur touchait la part déjà rendue à l'expéditeur, payée par la plateforme. La
 * libération doit être bloquée et signalée, comme pour un litige.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryEventListenerPartialRefundTest {

    /** Capture déjà faite par défaut : PaymentIntent succeeded, aucun charge id renvoyé. */
    private final com.yadony.api.payments.EscrowCaptureService escrowCapture = org.mockito.Mockito.mock(com.yadony.api.payments.EscrowCaptureService.class, invocation -> new com.yadony.api.payments.EscrowCaptureService.Outcome(null, false));

    @org.mockito.Mock com.yadony.api.payments.hold.PayoutHoldPolicy holdPolicy;
    @org.mockito.Mock com.yadony.api.admin.AdminAlertEscalator alertEscalator;
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
        // Ronde 1, point 5 : payoutInitiator est désormais un paramètre constructeur — null ici,
        // jamais déréférencé (paiement disputé, bloqué avant tout branchement par rail).
        listener = new DeliveryEventListener(paymentRepository, userRepository,
                auditService, eventPublisher, bidRepository, adminAlert, voucherService, null, holdPolicy, alertEscalator,
                escrowCapture, org.mockito.Mockito.mock(com.yadony.api.payments.StripeTransferLookup.class), org.mockito.Mockito.mock(com.yadony.api.disputes.DisputeRepository.class));
    }

    private static void setId(Object entity, UUID id) throws Exception {
        Field field = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    @Test
    void remboursement_partiel_bloque_le_versement_et_leve_une_alerte() throws Exception {
        UUID bidId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        PaymentEntity payment = new PaymentEntity();
        setId(payment, paymentId);
        payment.setBidId(bidId);
        payment.setStripePaymentIntentId("pi_partial");
        payment.setAmount(BigDecimal.valueOf(50));
        payment.setCommissionAmount(BigDecimal.valueOf(6));
        payment.setRefundedAmount(BigDecimal.valueOf(20));
        payment.setStatus(PaymentStatus.ESCROW);
        payment.setLegacyDestinationCharge(false);

        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(payment));

        DeliveryConfirmedEvent event = new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), travelerId);

        try (MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            listener.handleDeliveryConfirmed(event);

            transferStatic.verifyNoInteractions();
        }

        // Aucun claim ESCROW → RELEASED : le paiement reste en séquestre pour arbitrage.
        verify(paymentRepository, never()).markReleasedIfEscrow(any(), any());
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.ESCROW);
        verify(auditService).log(
                eq("PAYMENT"), eq(paymentId), eq("DELIVERY_TRANSFER_BLOCKED_PARTIAL_REFUND"), any(), anyMap());
        verify(alertEscalator).raiseOnce(eq("PARTIAL_REFUND_HOLD_" + paymentId), anyString(), anyMap());
        verify(eventPublisher, never()).publishEvent(any(PaymentReleasedEvent.class));
    }
}
