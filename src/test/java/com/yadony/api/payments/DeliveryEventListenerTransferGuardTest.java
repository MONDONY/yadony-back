package com.yadony.api.payments;

import com.stripe.exception.ApiConnectionException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Transfer;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.events.PaymentReleasedEvent;
import com.yadony.api.payments.hold.PayoutHoldPolicy;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Anti double Transfer (clé d'idempotence expirée au bout de 24 h) et gel du versement par un
 * litige ouvert par l'administration.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryEventListenerTransferGuardTest {

    private final EscrowCaptureService escrowCapture = mock(EscrowCaptureService.class,
            invocation -> new EscrowCaptureService.Outcome(null, false));

    @Mock PaymentRepository paymentRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock BidRepository bidRepository;
    @Mock AdminAlertService adminAlert;
    @Mock com.yadony.api.voucher.CommissionVoucherService voucherService;
    @Mock PayoutHoldPolicy holdPolicy;
    @Mock AdminAlertEscalator alertEscalator;
    @Mock StripeTransferLookup transferLookup;
    @Mock DisputeRepository disputeRepository;

    private DeliveryEventListener listener;
    private PaymentEntity payment;
    private final UUID paymentId = UUID.randomUUID();
    private final UUID bidId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new DeliveryEventListener(paymentRepository, userRepository, auditService, eventPublisher,
                bidRepository, adminAlert, voucherService, null, holdPolicy, alertEscalator, escrowCapture,
                transferLookup, disputeRepository);
        payment = new PaymentEntity();
        ReflectionTestUtils.setField(payment, "id", paymentId);
        ReflectionTestUtils.setField(payment, "createdAt", LocalDateTime.of(2026, 10, 1, 12, 0));
        payment.setBidId(bidId);
        payment.setStripePaymentIntentId("pi_1");
        payment.setStripeChargeId("ch_1");
        payment.setStatus(PaymentStatus.ESCROW);
        payment.setAmount(new BigDecimal("30.00"));
        payment.setCommissionAmount(new BigDecimal("3.60"));
        lenient().when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(payment));
        UserEntity traveler = new UserEntity();
        traveler.setStripeAccountId("acct_t");
        lenient().when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
    }

    private DeliveryConfirmedEvent delivery() {
        return new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), travelerId);
    }

    @Test
    void transferDejaEmisChezStripe_aucunSecondTransfer_baseRealignee() throws Exception {
        when(paymentRepository.markReleasedIfEscrow(eq(paymentId), any())).thenReturn(1);
        when(transferLookup.findExistingTransfer(paymentId, "acct_t", payment.getCreatedAt()))
                .thenReturn(Optional.of("tr_existing"));

        try (MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class);
             MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            listener.handleDeliveryConfirmed(delivery());
            transferStatic.verifyNoInteractions();
        }

        verify(paymentRepository).recordStripeTransferId(paymentId, "tr_existing");
        verify(auditService).log(eq("PAYMENT"), eq(paymentId), eq("TRANSFER_ALREADY_EXISTS_REALIGNED"), eq(bidId),
                argThat((Map<String, Object> m) -> "tr_existing".equals(m.get("transferId"))
                        && "delivery".equals(m.get("source"))));
        verify(eventPublisher).publishEvent(any(PaymentReleasedEvent.class));
    }

    @Test
    void aucunTransferExistant_creeLeTransferEtTraceSonIdentifiant() throws Exception {
        when(paymentRepository.markReleasedIfEscrow(eq(paymentId), any())).thenReturn(1);
        when(transferLookup.findExistingTransfer(any(), anyString(), any())).thenReturn(Optional.empty());

        try (MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            Transfer created = mock(Transfer.class);
            when(created.getId()).thenReturn("tr_new");
            transferStatic.when(() -> Transfer.create(any(com.stripe.param.TransferCreateParams.class),
                    any(com.stripe.net.RequestOptions.class))).thenReturn(created);

            listener.handleDeliveryConfirmed(delivery());

            transferStatic.verify(() -> Transfer.create(any(com.stripe.param.TransferCreateParams.class),
                    any(com.stripe.net.RequestOptions.class)), times(1));
        }
        verify(paymentRepository).recordStripeTransferId(paymentId, "tr_new");
        verify(auditService, never()).log(any(), any(), eq("TRANSFER_ALREADY_EXISTS_REALIGNED"), any(), any());
    }

    @Test
    void lectureStripeEnEchec_aucunTransfer_claimAnnule() throws Exception {
        when(paymentRepository.markReleasedIfEscrow(eq(paymentId), any())).thenReturn(1);
        when(transferLookup.findExistingTransfer(any(), anyString(), any()))
                .thenThrow(new ApiConnectionException("timeout"));

        try (MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            assertThatThrownBy(() -> listener.handleDeliveryConfirmed(delivery()))
                    .isInstanceOf(IllegalStateException.class);
            transferStatic.verifyNoInteractions();
        }
        verify(paymentRepository, never()).recordStripeTransferId(any(), any());
    }

    @Test
    void litigeAdminOuvert_versementGele_aucunClaim() {
        when(disputeRepository.existsByBidIdAndStatusAndTypeStartingWith(bidId, "OPEN", "ADMIN_"))
                .thenReturn(true);

        EscrowReleaseOutcome outcome;
        try (MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            outcome = listener.releaseAfterLateEscrow(bidId, UUID.randomUUID(), travelerId, "late-escrow");
            transferStatic.verifyNoInteractions();
        }

        assertThat(outcome).isEqualTo(EscrowReleaseOutcome.BLOCKED_DISPUTE);
        assertThat(outcome.blockedByGuard()).isTrue();
        assertThat(outcome.released()).isFalse();
        verify(paymentRepository, never()).markReleasedIfEscrow(any(), any());
        verify(auditService).log(eq("PAYMENT"), eq(paymentId), eq("DELIVERY_TRANSFER_BLOCKED_DISPUTE"), eq(bidId), any());
        verify(alertEscalator).raiseOnce(eq(DeliveryEventListener.DISPUTE_HOLD_ALERT_PREFIX + paymentId), anyString(), any());
        assertThat((DeliveryEventListener.DISPUTE_HOLD_ALERT_PREFIX + paymentId).length())
                .isLessThanOrEqualTo(AdminAlertEscalator.TYPE_MAX_LENGTH);
    }
}
