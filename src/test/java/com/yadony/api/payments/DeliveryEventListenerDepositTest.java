package com.yadony.api.payments;

import com.stripe.model.Transfer;
import com.stripe.net.RequestOptions;
import com.stripe.param.TransferCreateParams;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.PayoutTrigger;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.events.PaymentReleasedEvent;
import com.yadony.api.payments.hold.PayoutHoldPolicy;
import com.yadony.api.payments.hold.PayoutHoldReason;
import com.yadony.api.payments.hold.PayoutHoldStatus;
import com.yadony.api.payments.mobilemoney.MobileMoneyPayoutInitiator;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import com.yadony.api.tracking.events.ParcelDepositedEvent;
import com.yadony.api.voucher.CommissionVoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Versement au dépôt (BB4) : un colis payé au dépôt et déposé est versé par le chemin de la livraison (mêmes gardes,
 * même claim, même clé Stripe, même payout pawaPay) ; un colis payé à la livraison ne l'est jamais au dépôt, et la
 * livraison d'un colis payé au dépôt ne verse rien.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DeliveryEventListener : versement au dépôt")
class DeliveryEventListenerDepositTest {

    /** Capture déjà faite : PaymentIntent succeeded, aucun charge id renvoyé. */
    private final EscrowCaptureService escrowCapture =
            mock(EscrowCaptureService.class, invocation -> new EscrowCaptureService.Outcome(null, false));

    @Mock PaymentRepository paymentRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock BidRepository bidRepository;
    @Mock AdminAlertService adminAlert;
    @Mock CommissionVoucherService voucherService;
    @Mock MobileMoneyPayoutInitiator payoutInitiator;
    @Mock PayoutHoldPolicy holdPolicy;
    @Mock AdminAlertEscalator alertEscalator;
    @Mock DisputeRepository disputeRepository;

    private DeliveryEventListener listener;
    private final UUID bidId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private BidEntity bid;
    private PaymentEntity payment;

    @BeforeEach
    void setUp() {
        listener = ReleaseTestSupport.listener(paymentRepository, userRepository, auditService, eventPublisher,
                bidRepository, adminAlert, voucherService, payoutInitiator, holdPolicy, alertEscalator, escrowCapture,
                mock(StripeTransferLookup.class), disputeRepository);
        bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", bidId);
        bid.setSenderId(senderId);
        bid.setStatus(BidStatus.HANDED_OVER);
        freeze(bid, PayoutTrigger.DEPOSIT);
        lenient().when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        lenient().when(bidRepository.findDepartScannedAt(bidId)).thenReturn(LocalDateTime.now().minusMinutes(1));

        payment = new PaymentEntity();
        ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
        payment.setBidId(bidId);
        payment.setRail(PaymentRail.STRIPE);
        payment.setStripePaymentIntentId("pi_deposit");
        payment.setStripeChargeId("ch_deposit");
        payment.setStatus(PaymentStatus.ESCROW);
        payment.setAmount(new BigDecimal("30.00"));
        payment.setCommissionAmount(new BigDecimal("3.60"));
        payment.setCurrency("EUR");
        lenient().when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(payment));
        lenient().when(paymentRepository.markReleasedIfEscrowAndUnguarded(any(), any())).thenReturn(1);
        UserEntity traveler = new UserEntity();
        traveler.setStripeAccountId("acct_carrier");
        lenient().when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
    }

    private static void freeze(BidEntity b, PayoutTrigger trigger) {
        ReflectionTestUtils.setField(b, "payoutTrigger", trigger);
        ReflectionTestUtils.setField(b, "payoutTriggerFrozenAt", LocalDateTime.now().minusDays(3));
    }

    private ParcelDepositedEvent deposited() {
        return new ParcelDepositedEvent(bidId, senderId, travelerId);
    }

    private void verifyNothingPaid() {
        verify(paymentRepository, never()).markReleasedIfEscrowAndUnguarded(any(), any());
        verifyNoInteractions(payoutInitiator);
        verify(eventPublisher, never()).publishEvent(any(PaymentReleasedEvent.class));
    }

    // ── Versement ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("carte (V2) : un Transfer du net, clé transfer-<paymentId>, audit ESCROW_RELEASED_DEPOSIT, versement « au dépôt »")
    void deposit_cardV2_transfersOnce() {
        try (MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            Transfer transfer = mock(Transfer.class);
            when(transfer.getId()).thenReturn("tr_deposit");
            ArgumentCaptor<TransferCreateParams> params = ArgumentCaptor.forClass(TransferCreateParams.class);
            ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
            transferStatic.when(() -> Transfer.create(params.capture(), options.capture())).thenReturn(transfer);

            assertThat(listener.releaseAtDeposit(deposited())).isEqualTo(EscrowReleaseOutcome.RELEASED);

            transferStatic.verify(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)),
                    times(1));
            assertThat(params.getValue().getAmount()).isEqualTo(2640L);
            assertThat(params.getValue().getDestination()).isEqualTo("acct_carrier");
            assertThat(options.getValue().getIdempotencyKey()).startsWith("transfer-" + payment.getId());
        }
        verify(paymentRepository, times(1)).markReleasedIfEscrowAndUnguarded(eq(payment.getId()), any());
        verify(auditService).log(eq("PAYMENT"), eq(payment.getId()), eq("ESCROW_RELEASED_DEPOSIT"), any(), anyMap());
        ArgumentCaptor<PaymentReleasedEvent> released = ArgumentCaptor.forClass(PaymentReleasedEvent.class);
        verify(eventPublisher).publishEvent(released.capture());
        assertThat(released.getValue().isDeposit()).isTrue();
        assertThat(released.getValue().getSenderId()).isEqualTo(senderId);
        verify(alertEscalator, never()).raiseOnce(anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("mobile money : un payout pawaPay du net, source « deposit », aucun appel Stripe")
    void deposit_mobileMoney_payoutOnce() {
        payment.setRail(PaymentRail.PAWAPAY);
        payment.setCurrency("XOF");
        payment.setAmount(new BigDecimal("20000"));
        payment.setCommissionAmount(new BigDecimal("2400"));

        try (MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            assertThat(listener.releaseAtDeposit(deposited())).isEqualTo(EscrowReleaseOutcome.RELEASED);
            transferStatic.verifyNoInteractions();
        }

        verify(payoutInitiator, times(1)).release(eq(payment), eq(bidId), eq(travelerId), any(BigDecimal.class),
                eq("deposit"));
    }

    // ── Rien ne part ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("colis en espèces payé au dépôt : aucun argent plateforme, rien ne part")
    void deposit_cash_releasesNothing() {
        bid.setPaymentMethod(PaymentMethod.CASH);

        assertThat(listener.releaseAtDeposit(deposited())).isEqualTo(EscrowReleaseOutcome.CASH);

        verify(paymentRepository, never()).findByBidId(any());
        verifyNothingPaid();
        verify(alertEscalator, never()).raiseOnce(anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("déclencheur « dépôt » sur un colis payé à la livraison : ignoré et audité, rien ne part")
    void depositOnDeliveryTriggerBid_releasesNothing() {
        freeze(bid, PayoutTrigger.DELIVERY);

        try (MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            assertThat(listener.releaseAtDeposit(deposited())).isEqualTo(EscrowReleaseOutcome.NOT_DEPOSITED);
            transferStatic.verifyNoInteractions();
        }

        verify(auditService).log(eq("BID"), eq(bidId), eq("DEPOSIT_RELEASE_IGNORED_DELIVERY_TRIGGER"), eq(travelerId),
                anyMap());
        verify(paymentRepository, never()).findByBidId(any());
        verifyNothingPaid();
    }

    @Test
    @DisplayName("colis payé au dépôt mais sans DEPART enregistré, ou sorti du parcours : rien ne part")
    void depositNotRecordedOrBidCancelled_releasesNothing() {
        when(bidRepository.findDepartScannedAt(bidId)).thenReturn(null);
        assertThat(listener.releaseAtDeposit(deposited())).isEqualTo(EscrowReleaseOutcome.NOT_DEPOSITED);

        // Statut hors parcours : refusé avant même de lire le DEPART.
        bid.setStatus(BidStatus.CANCELLED);
        assertThat(listener.releaseAtDeposit(deposited())).isEqualTo(EscrowReleaseOutcome.NOT_DEPOSITED);

        bid.setStatus(BidStatus.ACCEPTED);
        assertThat(listener.releaseAtDeposit(deposited())).isEqualTo(EscrowReleaseOutcome.NOT_DEPOSITED);

        verify(auditService, times(3)).log(eq("BID"), eq(bidId), eq("DEPOSIT_RELEASE_IGNORED_NOT_DEPOSITED"),
                eq(travelerId), anyMap());
        verifyNothingPaid();
    }

    @Test
    @DisplayName("livraison d'un colis payé au dépôt : rien de financier, audit DELIVERY_RELEASE_SKIPPED_DEPOSIT")
    void deliveryOfDepositBid_releasesNothing() {
        bid.setStatus(BidStatus.COMPLETED);

        try (MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            listener.handleDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, senderId, travelerId));
            transferStatic.verifyNoInteractions();
        }

        verify(auditService).log(eq("BID"), eq(bidId), eq("DELIVERY_RELEASE_SKIPPED_DEPOSIT"), eq(travelerId), anyMap());
        verify(paymentRepository, never()).findByBidId(any());
        verifyNothingPaid();
    }

    @Test
    @DisplayName("dépôt rejoué après le versement : paiement déjà sorti du séquestre, rien ne part")
    void depositReplayedAfterRelease_releasesNothing() {
        payment.setStatus(PaymentStatus.RELEASED);

        assertThat(listener.releaseAtDeposit(deposited())).isEqualTo(EscrowReleaseOutcome.NOT_IN_ESCROW);

        verifyNothingPaid();
    }

    @Test
    @DisplayName("colis annulé (ou refusé) après la première lecture, avant le claim : relu sous verrou, rien ne part")
    void depositCancelledBeforeClaim_relockedReadStopsRelease() {
        BidEntity cancelled = new BidEntity();
        ReflectionTestUtils.setField(cancelled, "id", bidId);
        cancelled.setSenderId(senderId);
        cancelled.setStatus(BidStatus.CANCELLED);
        freeze(cancelled, PayoutTrigger.DEPOSIT);
        // 1re lecture (pré-filtre) : déposé ; 2e lecture, sous le verrou du colis : annulé entre-temps.
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid), Optional.of(cancelled));

        try (MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            assertThat(listener.releaseAtDeposit(deposited())).isEqualTo(EscrowReleaseOutcome.NOT_DEPOSITED);
            transferStatic.verifyNoInteractions();
        }

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(bidRepository);
        order.verify(bidRepository).lockForUpdate(bidId);
        order.verify(bidRepository).findById(bidId);
        verify(auditService).log(eq("BID"), eq(bidId), eq("DEPOSIT_RELEASE_IGNORED_NOT_DEPOSITED"), eq(travelerId),
                anyMap());
        verifyNothingPaid();
        verify(alertEscalator, never()).raiseOnce(anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("M2 : séquestre tardif d'un colis payé au dépôt annulé avant le claim : relu sous verrou, rien ne part")
    void lateEscrowOfDepositBidCancelledBeforeClaim_relockedReadStopsRelease() {
        BidEntity cancelled = new BidEntity();
        ReflectionTestUtils.setField(cancelled, "id", bidId);
        cancelled.setSenderId(senderId);
        cancelled.setStatus(BidStatus.CANCELLED);
        freeze(cancelled, PayoutTrigger.DEPOSIT);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid), Optional.of(cancelled));

        try (MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            assertThat(listener.releaseAfterLateEscrow(bidId, senderId, travelerId, "late-escrow"))
                    .isEqualTo(EscrowReleaseOutcome.NOT_DEPOSITED);
            transferStatic.verifyNoInteractions();
        }
        verifyNothingPaid();
    }

    @Test
    @DisplayName("claim perdu (un autre traitement a versé) : rien ne part")
    void depositClaimLost_releasesNothing() {
        when(paymentRepository.markReleasedIfEscrowAndUnguarded(any(), any())).thenReturn(0);
        when(paymentRepository.findStatusById(payment.getId())).thenReturn(Optional.of(PaymentStatus.RELEASED));

        try (MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            assertThat(listener.releaseAtDeposit(deposited())).isEqualTo(EscrowReleaseOutcome.ALREADY_RELEASED);
            transferStatic.verifyNoInteractions();
        }
        verify(eventPublisher, never()).publishEvent(any(PaymentReleasedEvent.class));
    }

    // ── Gardes : mêmes que la livraison, le séquestre reste en place ─────────

    @Test
    @DisplayName("litige ouvert par l'administration : versement gelé, séquestre en place")
    void deposit_adminDisputeOpen_staysEscrow() {
        when(disputeRepository.existsByBidIdAndStatusAndTypeStartingWith(eq(bidId), anyString(), anyString()))
                .thenReturn(true);

        assertThat(listener.releaseAtDeposit(deposited())).isEqualTo(EscrowReleaseOutcome.BLOCKED_DISPUTE);

        verifyNothingPaid();
    }

    @Test
    @DisplayName("transporteur gelé : versement retenu, séquestre en place")
    void deposit_travelerHeld_staysEscrow() {
        when(holdPolicy.isHeld(travelerId)).thenReturn(true);
        when(holdPolicy.statusOf(travelerId)).thenReturn(new PayoutHoldStatus(LocalDateTime.now().minusDays(1),
                List.of(PayoutHoldReason.values()[0])));

        assertThat(listener.releaseAtDeposit(deposited())).isEqualTo(EscrowReleaseOutcome.PAYOUT_HELD);

        verifyNothingPaid();
    }

    @Test
    @DisplayName("paiement partiellement remboursé : versement bloqué")
    void deposit_partialRefund_blocked() {
        payment.setRefundedAmount(new BigDecimal("5.00"));

        assertThat(listener.releaseAtDeposit(deposited())).isEqualTo(EscrowReleaseOutcome.BLOCKED_PARTIAL_REFUND);

        verifyNothingPaid();
    }

    @Test
    @DisplayName("litige bancaire : versement bloqué")
    void deposit_chargeback_blocked() {
        payment.setDisputed(true);

        assertThat(listener.releaseAtDeposit(deposited())).isEqualTo(EscrowReleaseOutcome.BLOCKED_CHARGEBACK);

        verifyNothingPaid();
    }

    @Test
    @DisplayName("compte Connect désactivé : aucun Transfer tenté, séquestre en place")
    void deposit_connectUnusable_staysEscrow() {
        UserEntity disabled = new UserEntity();
        disabled.setStripeAccountId("acct_carrier");
        disabled.setStripeAccountStatus(StripeAccountStatus.DISABLED);
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(disabled));

        assertThat(listener.releaseAtDeposit(deposited())).isEqualTo(EscrowReleaseOutcome.STRIPE_ACCOUNT_UNUSABLE);

        verifyNothingPaid();
    }

    @Test
    @DisplayName("compte Connect pas encore créé : échec rattrapé, alerte DEPOSIT_RELEASE_FAILED_<colis>, rien ne part")
    void deposit_noConnectAccount_alertsAndKeepsEscrow() {
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(new UserEntity()));

        try (MockedStatic<Transfer> transferStatic = mockStatic(Transfer.class)) {
            assertThat(listener.releaseAtDeposit(deposited())).isEqualTo(EscrowReleaseOutcome.TRANSFER_FAILED);
            transferStatic.verifyNoInteractions();
        }

        verify(alertEscalator).raiseOnce(eq(DeliveryEventListener.DEPOSIT_FAILED_ALERT_PREFIX + bidId), anyString(),
                anyMap());
        verify(eventPublisher, never()).publishEvent(any(PaymentReleasedEvent.class));
    }

    @Test
    @DisplayName("aucun paiement retrouvé pour un colis non espèces : alerte, rien ne part")
    void deposit_noPayment_alerts() {
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());

        assertThat(listener.releaseAtDeposit(deposited())).isEqualTo(EscrowReleaseOutcome.NO_PAYMENT);

        verify(alertEscalator).raiseOnce(eq(DeliveryEventListener.DEPOSIT_FAILED_ALERT_PREFIX + bidId), anyString(),
                anyMap());
        verifyNothingPaid();
    }

    @Test
    @DisplayName("paiement jamais passé en séquestre au dépôt : alerte « colis déposé », rien ne part")
    void deposit_paymentStillPending_alertsDeposited() {
        payment.setStatus(PaymentStatus.PENDING);

        assertThat(listener.releaseAtDeposit(deposited())).isEqualTo(EscrowReleaseOutcome.NOT_IN_ESCROW);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(alertEscalator).raiseOnce(eq(DeliveryEventListener.NOT_IN_ESCROW_ALERT_PREFIX + payment.getId()),
                detail.capture(), anyMap());
        assertThat(detail.getValue()).startsWith("Colis déposé");
        verifyNothingPaid();
    }

    @Test
    @DisplayName("handleParcelDeposited délègue au versement au dépôt")
    void handleParcelDeposited_releases() {
        payment.setRail(PaymentRail.PAWAPAY);

        listener.handleParcelDeposited(deposited());

        verify(payoutInitiator).release(eq(payment), eq(bidId), eq(travelerId), any(BigDecimal.class), eq("deposit"));
    }
}
