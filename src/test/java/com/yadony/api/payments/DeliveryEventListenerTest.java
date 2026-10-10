package com.yadony.api.payments;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.events.PaymentReleasedEvent;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Transfer;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCaptureParams;
import com.stripe.param.TransferCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveryEventListenerTest {

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
        // jamais déréférencé puisque tous les paiements de cette classe sont de rail STRIPE.
        listener = new DeliveryEventListener(paymentRepository, userRepository,
                auditService, eventPublisher, bidRepository, adminAlert, voucherService, null, holdPolicy, alertEscalator,
                escrowCapture, org.mockito.Mockito.mock(com.yadony.api.payments.StripeTransferLookup.class), org.mockito.Mockito.mock(com.yadony.api.disputes.DisputeRepository.class));
    }

    private PaymentEntity payment(boolean legacy, PaymentStatus status, String chargeId) {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(UUID.randomUUID());
        p.setStripePaymentIntentId("pi_xxx");
        p.setStripeChargeId(chargeId);
        p.setStatus(status);
        p.setLegacyDestinationCharge(legacy);
        p.setAmount(new BigDecimal("30.00"));
        p.setCommissionAmount(new BigDecimal("3.60"));
        return p;
    }

    private DeliveryConfirmedEvent event(UUID bidId, UUID travelerId) {
        return new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), travelerId);
    }

    private UserEntity traveler() {
        UserEntity u = new UserEntity();
        u.setStripeAccountId("acct_xyz");
        return u;
    }

    @Test
    void legacy_path_calls_capture() throws StripeException {
        PaymentEntity p = payment(true, PaymentStatus.ESCROW, "ch_legacy");
        UUID travelerId = UUID.randomUUID();
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));
        when(paymentRepository.markReleasedIfEscrowAndUnguarded(any(), any())).thenReturn(1);

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            ArgumentCaptor<RequestOptions> optsCaptor = ArgumentCaptor.forClass(RequestOptions.class);
            when(pi.capture(any(PaymentIntentCaptureParams.class), optsCaptor.capture())).thenReturn(pi);
            piStatic.when(() -> PaymentIntent.retrieve("pi_xxx")).thenReturn(pi);

            listener.handleDeliveryConfirmed(event(p.getBidId(), travelerId));

            verify(pi).capture(any(PaymentIntentCaptureParams.class), any(RequestOptions.class));
            // Clé d'idempotence stable capture-{paymentId} contre la double capture
            assertThat(optsCaptor.getValue().getIdempotencyKey()).startsWith("capture-");
            transferStatic.verifyNoInteractions();
        }

        // Transition de statut faite par le claim atomique markReleasedIfEscrow
        verify(paymentRepository).markReleasedIfEscrowAndUnguarded(any(), any());
        verify(auditService).log(eq("PAYMENT"), any(), eq("ESCROW_RELEASED_LEGACY"), any(), any());
        verify(eventPublisher).publishEvent(any(PaymentReleasedEvent.class));
    }

    @Test
    void v2_path_calls_transfer_with_net_amount_in_cents() {
        PaymentEntity p = payment(false, PaymentStatus.ESCROW, "ch_new");
        UUID travelerId = UUID.randomUUID();
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));
        when(paymentRepository.markReleasedIfEscrowAndUnguarded(any(), any())).thenReturn(1);
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler()));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            Transfer transfer = mock(Transfer.class);
            ArgumentCaptor<TransferCreateParams> captor = ArgumentCaptor.forClass(TransferCreateParams.class);
            ArgumentCaptor<RequestOptions> optsCaptor = ArgumentCaptor.forClass(RequestOptions.class);
            transferStatic.when(() -> Transfer.create(captor.capture(), optsCaptor.capture()))
                    .thenReturn(transfer);

            listener.handleDeliveryConfirmed(event(p.getBidId(), travelerId));

            piStatic.verifyNoInteractions();
            TransferCreateParams params = captor.getValue();
            assertThat(params.getAmount()).isEqualTo(2640L); // (30 - 3.60) * 100
            assertThat(params.getCurrency()).isEqualTo("eur");
            assertThat(params.getDestination()).isEqualTo("acct_xyz");
            assertThat(params.getSourceTransaction()).isEqualTo("ch_new");
            // Clé d'idempotence stable transfer-{paymentId} contre le double Transfer
            assertThat(optsCaptor.getValue().getIdempotencyKey()).startsWith("transfer-");
        }

        verify(paymentRepository).markReleasedIfEscrowAndUnguarded(any(), any());
        verify(auditService).log(eq("PAYMENT"), any(), eq("ESCROW_RELEASED_TRANSFER"), any(), any());
        verify(eventPublisher).publishEvent(any(PaymentReleasedEvent.class));
    }

    @Test
    void v2_path_travelerHoldsVoucher_transfersToppedUpByHalfCommission() {
        // Bon de parrainage (lot 3) : commission 3.60, facteur 0.5 → 1.80 rendus au
        // voyageur. net = (30 - 3.60) + 1.80 = 28.20. Le brut expéditeur ne bouge pas.
        PaymentEntity p = payment(false, PaymentStatus.ESCROW, "ch_new");
        UUID travelerId = UUID.randomUUID();
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));
        when(paymentRepository.markReleasedIfEscrowAndUnguarded(any(), any())).thenReturn(1);
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler()));
        com.yadony.api.voucher.CommissionVoucherEntity voucher =
                mock(com.yadony.api.voucher.CommissionVoucherEntity.class);
        lenient().when(voucher.getFactor()).thenReturn(new BigDecimal("0.50"));
        when(voucherService.consume(travelerId, p.getBidId())).thenReturn(Optional.of(voucher));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            Transfer transfer = mock(Transfer.class);
            ArgumentCaptor<TransferCreateParams> captor = ArgumentCaptor.forClass(TransferCreateParams.class);
            transferStatic.when(() -> Transfer.create(captor.capture(), any(RequestOptions.class)))
                    .thenReturn(transfer);

            listener.handleDeliveryConfirmed(event(p.getBidId(), travelerId));

            assertThat(captor.getValue().getAmount()).isEqualTo(2820L); // 28.20 * 100
        }
    }

    @Test
    void v2_path_noVoucher_transfersUnchangedNetAmount() {
        PaymentEntity p = payment(false, PaymentStatus.ESCROW, "ch_new");
        UUID travelerId = UUID.randomUUID();
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));
        when(paymentRepository.markReleasedIfEscrowAndUnguarded(any(), any())).thenReturn(1);
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler()));
        when(voucherService.consume(travelerId, p.getBidId())).thenReturn(Optional.empty());

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            Transfer transfer = mock(Transfer.class);
            ArgumentCaptor<TransferCreateParams> captor = ArgumentCaptor.forClass(TransferCreateParams.class);
            transferStatic.when(() -> Transfer.create(captor.capture(), any(RequestOptions.class)))
                    .thenReturn(transfer);

            listener.handleDeliveryConfirmed(event(p.getBidId(), travelerId));

            assertThat(captor.getValue().getAmount()).isEqualTo(2640L);
        }
    }

    @Test
    void v2_path_uses_persisted_xof_currency_and_zero_decimal_units() {
        PaymentEntity p = payment(false, PaymentStatus.ESCROW, "ch_xof");
        p.setCurrency("xof");
        p.setAmount(new BigDecimal("30000"));
        p.setCommissionAmount(new BigDecimal("3600"));
        UUID travelerId = UUID.randomUUID();
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));
        when(paymentRepository.markReleasedIfEscrowAndUnguarded(any(), any())).thenReturn(1);
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler()));

        try (MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            ArgumentCaptor<TransferCreateParams> captor = ArgumentCaptor.forClass(TransferCreateParams.class);
            transferStatic.when(() -> Transfer.create(captor.capture(), any(RequestOptions.class)))
                    .thenReturn(mock(Transfer.class));

            listener.handleDeliveryConfirmed(event(p.getBidId(), travelerId));

            assertThat(captor.getValue().getAmount()).isEqualTo(26400L);
            assertThat(captor.getValue().getCurrency()).isEqualTo("xof");
        }
    }

    @Test
    void release_skipped_when_atomic_claim_lost() {
        // Un traitement concurrent a déjà sorti le paiement d'ESCROW entre la lecture
        // et le claim → aucun appel Stripe, aucun audit, aucun event.
        PaymentEntity p = payment(false, PaymentStatus.ESCROW, "ch_race");
        UUID travelerId = UUID.randomUUID();
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));
        when(paymentRepository.markReleasedIfEscrowAndUnguarded(any(), any())).thenReturn(0);

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            listener.handleDeliveryConfirmed(event(p.getBidId(), travelerId));
            piStatic.verifyNoInteractions();
            transferStatic.verifyNoInteractions();
        }
        verifyNoInteractions(auditService);
        verify(eventPublisher, never()).publishEvent(any(PaymentReleasedEvent.class));
    }

    @Test
    void idempotent_when_already_released() {
        PaymentEntity p = payment(false, PaymentStatus.RELEASED, "ch_x");
        UUID travelerId = UUID.randomUUID();
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            listener.handleDeliveryConfirmed(event(p.getBidId(), travelerId));
            piStatic.verifyNoInteractions();
            transferStatic.verifyNoInteractions();
        }
        verifyNoInteractions(auditService);
        verify(eventPublisher, never()).publishEvent(any(PaymentReleasedEvent.class));
    }

    @Test
    void no_op_when_payment_not_found() {
        UUID bidId = UUID.randomUUID();
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());
        assertThatNoException().isThrownBy(() ->
                listener.handleDeliveryConfirmed(event(bidId, UUID.randomUUID())));
        verifyNoInteractions(auditService);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void v2_path_without_charge_id_omits_source_transaction() {
        PaymentEntity p = payment(false, PaymentStatus.ESCROW, null);
        UUID travelerId = UUID.randomUUID();
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));
        when(paymentRepository.markReleasedIfEscrowAndUnguarded(any(), any())).thenReturn(1);
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler()));

        try (MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            ArgumentCaptor<TransferCreateParams> captor = ArgumentCaptor.forClass(TransferCreateParams.class);
            transferStatic.when(() -> Transfer.create(captor.capture(), any(RequestOptions.class)))
                    .thenReturn(mock(Transfer.class));

            listener.handleDeliveryConfirmed(event(p.getBidId(), travelerId));

            assertThat(captor.getValue().getSourceTransaction()).isNull();
        }
    }

    @Test
    void cash_bid_skips_stripe_operations() {
        UUID bidId = UUID.randomUUID();
        BidEntity bid = new BidEntity();
        bid.setPaymentMethod(PaymentMethod.CASH);
        when(bidRepository.findById(bidId)).thenReturn(java.util.Optional.of(bid));

        try (MockedStatic<com.stripe.model.PaymentIntent> piStatic =
                     mockStatic(com.stripe.model.PaymentIntent.class);
             MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            listener.handleDeliveryConfirmed(event(bidId, UUID.randomUUID()));
            piStatic.verifyNoInteractions();
            transferStatic.verifyNoInteractions();
        }
        verifyNoInteractions(paymentRepository, auditService);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void negotiation_thread_payment_released_via_thread_fallback() {
        // Regression: negotiation / dedicated-trip escrow is keyed on the thread
        // (bid_id = NULL). findByBidId returns empty → the payout used to be skipped,
        // so the traveler was never paid. The thread fallback must release it, and the
        // audit / PaymentReleasedEvent must use the delivered bid id (payment.getBidId()
        // is null here → would otherwise NPE / lose the reference).
        UUID bidId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();

        BidEntity bid = new BidEntity();
        bid.setPaymentMethod(PaymentMethod.STRIPE);
        bid.setLinkedNegotiationThreadId(threadId);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        PaymentEntity p = payment(false, PaymentStatus.ESCROW, "ch_thread");
        p.setBidId(null); // thread-keyed payment
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());
        when(paymentRepository.findByNegotiationThreadId(threadId)).thenReturn(Optional.of(p));
        when(paymentRepository.markReleasedIfEscrowAndUnguarded(any(), any())).thenReturn(1);
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler()));

        try (MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            ArgumentCaptor<TransferCreateParams> captor = ArgumentCaptor.forClass(TransferCreateParams.class);
            transferStatic.when(() -> Transfer.create(captor.capture(), any(RequestOptions.class)))
                    .thenReturn(mock(Transfer.class));

            listener.handleDeliveryConfirmed(event(bidId, travelerId));

            TransferCreateParams params = captor.getValue();
            assertThat(params.getAmount()).isEqualTo(2640L); // (30 - 3.60) * 100
            assertThat(params.getDestination()).isEqualTo("acct_xyz");
            assertThat(params.getSourceTransaction()).isEqualTo("ch_thread");
            assertThat(params.getMetadata().get("bid_id")).isEqualTo(bidId.toString());
        }

        verify(paymentRepository).markReleasedIfEscrowAndUnguarded(any(), any());
        // actor id must be the delivered bid, not the null payment.getBidId()
        verify(auditService).log(eq("PAYMENT"), any(), eq("ESCROW_RELEASED_TRANSFER"), eq(bidId), any());
        verify(eventPublisher).publishEvent(any(PaymentReleasedEvent.class));
    }

    @Test
    void transfer_failure_rolls_back_claim() {
        PaymentEntity p = payment(false, PaymentStatus.ESCROW, "ch_fail");
        UUID travelerId = UUID.randomUUID();
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));
        when(paymentRepository.markReleasedIfEscrowAndUnguarded(any(), any())).thenReturn(1);
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler()));

        try (MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            transferStatic.when(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)))
                    .thenThrow(mock(com.stripe.exception.InvalidRequestException.class));

            // L'exception est propagée pour faire rollback la transaction REQUIRES_NEW :
            // le claim ESCROW → RELEASED est annulé, le backstop admin J+48 garde la main.
            assertThatThrownBy(() ->
                    listener.handleDeliveryConfirmed(event(p.getBidId(), travelerId)))
                    .isInstanceOf(IllegalStateException.class);
        }
        verify(auditService, never()).log(any(), any(), any(), any(), any());
        verify(eventPublisher, never()).publishEvent(any(PaymentReleasedEvent.class));
    }

    // ── FLUTTER-E2 : colis « non réclamé » au terme de la garde ──

    @Test
    void colisNonReclame_verseLeNetParLeMemeTransfertQueLaLivraison() {
        PaymentEntity p = payment(false, PaymentStatus.ESCROW, "ch_unclaimed");
        UUID travelerId = UUID.randomUUID();
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));
        when(paymentRepository.markReleasedIfEscrowAndUnguarded(any(), any())).thenReturn(1);
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler()));

        try (MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            ArgumentCaptor<TransferCreateParams> captor = ArgumentCaptor.forClass(TransferCreateParams.class);
            ArgumentCaptor<RequestOptions> optsCaptor = ArgumentCaptor.forClass(RequestOptions.class);
            transferStatic.when(() -> Transfer.create(captor.capture(), optsCaptor.capture()))
                    .thenReturn(mock(Transfer.class));

            listener.handleParcelUnclaimed(new com.yadony.api.cancellation.events.ParcelUnclaimedEvent(
                    p.getBidId(), UUID.randomUUID(), travelerId, UUID.randomUUID()));

            assertThat(captor.getValue().getAmount()).isEqualTo(2640L);
            // Même clé que la livraison : jamais deux Transfers pour le même paiement.
            assertThat(optsCaptor.getValue().getIdempotencyKey()).isEqualTo("transfer-" + p.getId());
        }
        verify(auditService).log(eq("PAYMENT"), any(), eq("ESCROW_RELEASED_UNCLAIMED"), any(), any());
        verify(eventPublisher).publishEvent(any(PaymentReleasedEvent.class));
    }

    @Test
    void colisNonReclame_paiementDejaVerse_aucunTransfert() {
        PaymentEntity p = payment(false, PaymentStatus.RELEASED, "ch_x");
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));

        try (MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            listener.handleParcelUnclaimed(new com.yadony.api.cancellation.events.ParcelUnclaimedEvent(
                    p.getBidId(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
            transferStatic.verifyNoInteractions();
        }
        verify(paymentRepository, never()).markReleasedIfEscrowAndUnguarded(any(), any());
    }

    // ── Séquestre carte non capturé (constat du 10/10, paiements de négociation) ──

    private DeliveryEventListener listenerWith(EscrowCaptureService capture) {
        return new DeliveryEventListener(paymentRepository, userRepository,
                auditService, eventPublisher, bidRepository, adminAlert, voucherService, null, holdPolicy, alertEscalator,
                capture, org.mockito.Mockito.mock(com.yadony.api.payments.StripeTransferLookup.class), org.mockito.Mockito.mock(com.yadony.api.disputes.DisputeRepository.class));
    }

    @Test
    void v2_requiresCapture_capturesBeforeClaimThenTransfers() {
        PaymentEntity p = payment(false, PaymentStatus.ESCROW, null);
        org.springframework.test.util.ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        UUID travelerId = UUID.randomUUID();
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));
        when(paymentRepository.markReleasedIfEscrowAndUnguarded(any(), any())).thenReturn(1);
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler()));
        EscrowCaptureService capture = mock(EscrowCaptureService.class);
        when(capture.ensureCaptured(p.getId(), "delivery")).thenReturn(new EscrowCaptureService.Outcome("ch_captured", true));

        try (MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            ArgumentCaptor<TransferCreateParams> captor = ArgumentCaptor.forClass(TransferCreateParams.class);
            transferStatic.when(() -> Transfer.create(captor.capture(), any(RequestOptions.class)))
                    .thenAnswer(inv -> {
                        // Le Transfer ne part qu'après la capture et le claim.
                        verify(capture).ensureCaptured(p.getId(), "delivery");
                        verify(paymentRepository).markReleasedIfEscrowAndUnguarded(any(), any());
                        return mock(Transfer.class);
                    });

            listenerWith(capture).handleDeliveryConfirmed(event(p.getBidId(), travelerId));

            org.mockito.InOrder order = inOrder(capture, paymentRepository);
            order.verify(capture).ensureCaptured(p.getId(), "delivery");
            order.verify(paymentRepository).markReleasedIfEscrowAndUnguarded(any(), any());
            assertThat(captor.getValue().getSourceTransaction()).isEqualTo("ch_captured");
            assertThat(captor.getValue().getAmount()).isEqualTo(2640L);
        }
        verify(auditService).log(eq("PAYMENT"), any(), eq("ESCROW_RELEASED_TRANSFER"), any(), any());
    }

    @Test
    void v2_captureFailure_noClaimNoTransfer_staysEscrowWithAudit() {
        PaymentEntity p = payment(false, PaymentStatus.ESCROW, "ch_x");
        org.springframework.test.util.ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        UUID travelerId = UUID.randomUUID();
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));
        EscrowCaptureService capture = mock(EscrowCaptureService.class);
        when(capture.ensureCaptured(p.getId(), "delivery"))
                .thenThrow(new EscrowCaptureService.EscrowCaptureException("PaymentIntent canceled", "canceled", null));

        try (MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            assertThatNoException().isThrownBy(() ->
                    listenerWith(capture).handleDeliveryConfirmed(event(p.getBidId(), travelerId)));
            transferStatic.verifyNoInteractions();
        }
        verify(paymentRepository, never()).markReleasedIfEscrowAndUnguarded(any(), any());
        verify(auditService).log(eq("PAYMENT"), eq(p.getId()), eq("DELIVERY_RELEASE_BLOCKED_CAPTURE_FAILED"), any(),
                argThat(m -> "canceled".equals(m.get("piStatus"))));
        verify(eventPublisher, never()).publishEvent(any());
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.ESCROW);
    }

    @Test
    void legacy_doesNotGoThroughEscrowCapture() {
        PaymentEntity p = payment(true, PaymentStatus.ESCROW, "ch_legacy");
        when(paymentRepository.findByBidId(p.getBidId())).thenReturn(Optional.of(p));
        when(paymentRepository.markReleasedIfEscrowAndUnguarded(any(), any())).thenReturn(1);
        EscrowCaptureService capture = mock(EscrowCaptureService.class);

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            piStatic.when(() -> PaymentIntent.retrieve("pi_xxx")).thenReturn(pi);
            listenerWith(capture).handleDeliveryConfirmed(event(p.getBidId(), UUID.randomUUID()));
        }
        verifyNoInteractions(capture);
    }
}
