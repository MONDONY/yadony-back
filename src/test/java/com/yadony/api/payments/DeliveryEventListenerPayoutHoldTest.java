package com.yadony.api.payments;

import com.stripe.model.PaymentIntent;
import com.stripe.model.Transfer;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.events.PaymentReleasedEvent;
import com.yadony.api.payments.hold.PayoutHoldPolicy;
import com.yadony.api.payments.hold.PayoutHoldReason;
import com.yadony.api.payments.hold.PayoutHoldStatus;
import com.yadony.api.payments.mobilemoney.MobileMoneyPayoutInitiator;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import com.yadony.api.voucher.CommissionVoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Versement retenu a la livraison quand le voyageur est gele (banni ou KYC retire), sur les trois
 * rails : le paiement reste ESCROW, rien ne part, l'administrateur est alerte.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryEventListenerPayoutHoldTest {

    /** Capture déjà faite par défaut : PaymentIntent succeeded, aucun charge id renvoyé. */
    private final com.yadony.api.payments.EscrowCaptureService escrowCapture = org.mockito.Mockito.mock(com.yadony.api.payments.EscrowCaptureService.class, invocation -> new com.yadony.api.payments.EscrowCaptureService.Outcome(null, false));

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

    private DeliveryEventListener listener;
    private PaymentEntity payment;
    private BidEntity bid;
    private final UUID travelerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new DeliveryEventListener(paymentRepository, userRepository, auditService, eventPublisher,
                bidRepository, adminAlert, voucherService, payoutInitiator, holdPolicy, alertEscalator,
                escrowCapture, org.mockito.Mockito.mock(com.yadony.api.payments.StripeTransferLookup.class), org.mockito.Mockito.mock(com.yadony.api.disputes.DisputeRepository.class));
        bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", UUID.randomUUID());
        bid.setPaymentMethod(PaymentMethod.STRIPE);
        payment = new PaymentEntity();
        ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
        payment.setBidId(bid.getId());
        payment.setStripePaymentIntentId("pi_hold");
        payment.setStripeChargeId("ch_hold");
        payment.setStatus(PaymentStatus.ESCROW);
        payment.setAmount(new BigDecimal("30.00"));
        payment.setCommissionAmount(new BigDecimal("3.60"));
        lenient().when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        lenient().when(paymentRepository.findByBidId(bid.getId())).thenReturn(Optional.of(payment));
    }

    private DeliveryConfirmedEvent event() {
        return new DeliveryConfirmedEvent(bid.getId(), UUID.randomUUID(), travelerId);
    }

    private void travelerHeld(PayoutHoldReason... reasons) {
        when(holdPolicy.isHeld(travelerId)).thenReturn(true);
        when(holdPolicy.statusOf(travelerId))
                .thenReturn(new PayoutHoldStatus(LocalDateTime.now().minusDays(1), List.of(reasons)));
    }

    private UserEntity traveler(StripeAccountStatus status) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", travelerId);
        u.setStripeAccountId("acct_hold");
        u.setStripeAccountStatus(status);
        return u;
    }

    private void assertHeldAndNothingPaid() {
        verify(paymentRepository, never()).markReleasedIfEscrow(any(), any());
        verify(paymentRepository).markPayoutHeld(eq(payment.getId()), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("PAYMENT"), eq(payment.getId()), eq("PAYOUT_HELD_BENEFICIARY"),
                eq(bid.getId()), payload.capture());
        assertThat(payload.getValue())
                .containsEntry("paymentId", payment.getId().toString())
                .containsEntry("travelerId", travelerId.toString())
                .containsEntry("reason", "BANNED");
        verify(alertEscalator).raiseOnce(eq("PAYOUT_HELD_" + payment.getId()), anyString(), anyMap());
        verify(eventPublisher, never()).publishEvent(any(PaymentReleasedEvent.class));
        verify(payoutInitiator, never()).release(any(), any(), any(), any(), any());
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.ESCROW);
    }

    @Test
    void carteV2_voyageurGele_rienNePart() {
        travelerHeld(PayoutHoldReason.BANNED);

        try (MockedStatic<Transfer> transfer = mockStatic(Transfer.class);
             MockedStatic<PaymentIntent> pi = mockStatic(PaymentIntent.class)) {
            listener.handleDeliveryConfirmed(event());
            transfer.verifyNoInteractions();
            pi.verifyNoInteractions();
        }
        assertHeldAndNothingPaid();
    }

    @Test
    void carteLegacy_voyageurGele_aucuneCapture() {
        payment.setLegacyDestinationCharge(true);
        travelerHeld(PayoutHoldReason.BANNED, PayoutHoldReason.KYC_REVOKED);

        try (MockedStatic<PaymentIntent> pi = mockStatic(PaymentIntent.class)) {
            listener.handleDeliveryConfirmed(event());
            pi.verifyNoInteractions();
        }
        assertHeldAndNothingPaid();
        verify(auditService).log(eq("PAYMENT"), eq(payment.getId()), eq("PAYOUT_HELD_BENEFICIARY"),
                eq(bid.getId()), eq(Map.of("paymentId", payment.getId().toString(),
                        "bidId", bid.getId().toString(), "travelerId", travelerId.toString(),
                        "reason", "BANNED", "reasons", "BANNED,KYC_REVOKED")));
    }

    @Test
    void mobileMoney_voyageurGele_aucunPayout() {
        bid.setPaymentMethod(PaymentMethod.MOBILE_MONEY);
        payment.setRail(PaymentRail.PAWAPAY);
        payment.setCurrency("XOF");
        travelerHeld(PayoutHoldReason.BANNED);

        listener.handleDeliveryConfirmed(event());

        assertHeldAndNothingPaid();
    }

    @Test
    void paiementDispute_laGardeLitigePrimeSurLeGel() {
        payment.setDisputed(true);

        listener.handleDeliveryConfirmed(event());

        verify(auditService).log(eq("PAYMENT"), eq(payment.getId()), eq("DELIVERY_TRANSFER_BLOCKED_CHARGEBACK"), any(), anyMap());
        verify(paymentRepository, never()).markPayoutHeld(any(), any());
        verify(paymentRepository, never()).markReleasedIfEscrow(any(), any());
    }

    @Test
    void voyageurNonGele_versementNormal() {
        when(holdPolicy.isHeld(travelerId)).thenReturn(false);
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler(StripeAccountStatus.ONBOARDING_COMPLETE)));
        when(paymentRepository.markReleasedIfEscrow(eq(payment.getId()), any())).thenReturn(1);
        when(voucherService.consume(any(), any())).thenReturn(Optional.empty());

        try (MockedStatic<Transfer> transfer = mockStatic(Transfer.class)) {
            listener.handleDeliveryConfirmed(event());
            transfer.verify(() -> Transfer.create(any(com.stripe.param.TransferCreateParams.class),
                    any(com.stripe.net.RequestOptions.class)));
        }
        verify(paymentRepository, never()).markPayoutHeld(any(), any());
        verify(eventPublisher).publishEvent(any(PaymentReleasedEvent.class));
    }

    @ParameterizedTest
    @EnumSource(value = StripeAccountStatus.class, names = {"DISABLED", "REJECTED"})
    void compteStripeInutilisable_aucunTransfer_paiementLaisseEnEscrow(StripeAccountStatus status) {
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler(status)));

        try (MockedStatic<Transfer> transfer = mockStatic(Transfer.class)) {
            listener.handleDeliveryConfirmed(event());
            transfer.verifyNoInteractions();
        }

        verify(paymentRepository, never()).markReleasedIfEscrow(any(), any());
        verify(paymentRepository, never()).markPayoutHeld(any(), any());
        verify(auditService).log(eq("PAYMENT"), eq(payment.getId()), eq("PAYOUT_BLOCKED_STRIPE_ACCOUNT_UNUSABLE"),
                eq(bid.getId()), eq(Map.of("paymentId", payment.getId().toString(), "bidId", bid.getId().toString(),
                        "travelerId", travelerId.toString(), "stripeAccountStatus", status.name())));
        verify(alertEscalator).raiseOnce(eq("PAYOUT_STRIPE_UNUSABLE_" + payment.getId()), anyString(), anyMap());
        verify(eventPublisher, never()).publishEvent(any(PaymentReleasedEvent.class));
    }

    @Test
    void compteStripeEnCoursDOnboarding_transferTente() {
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler(StripeAccountStatus.PENDING_ONBOARDING)));
        when(paymentRepository.markReleasedIfEscrow(eq(payment.getId()), any())).thenReturn(1);
        when(voucherService.consume(any(), any())).thenReturn(Optional.empty());

        try (MockedStatic<Transfer> transfer = mockStatic(Transfer.class)) {
            listener.handleDeliveryConfirmed(event());
            transfer.verify(() -> Transfer.create(any(com.stripe.param.TransferCreateParams.class),
                    any(com.stripe.net.RequestOptions.class)));
        }
        verify(alertEscalator, never()).raiseOnce(anyString(), anyString(), anyMap());
    }

    @Test
    void typesDAlerte_tiennentDansLaColonne() {
        String uuid = UUID.randomUUID().toString();
        assertThat(("PAYOUT_HELD_" + uuid).length()).isLessThanOrEqualTo(AdminAlertEscalator.TYPE_MAX_LENGTH);
        assertThat(("PAYOUT_STRIPE_UNUSABLE_" + uuid).length()).isLessThanOrEqualTo(AdminAlertEscalator.TYPE_MAX_LENGTH);
    }
}
