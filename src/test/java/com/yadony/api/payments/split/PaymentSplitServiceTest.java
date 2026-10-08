package com.yadony.api.payments.split;

import com.stripe.exception.ApiConnectionException;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.hold.PayoutHoldPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/** Partage chiffré admin (FLUTTER-E2) : validations, claim, exécution Stripe, reprise, idempotence. */
@ExtendWith(MockitoExtension.class)
class PaymentSplitServiceTest {

    @Mock PaymentRepository paymentRepository;
    @Mock PaymentSplitRepository splitRepository;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock UserRepository userRepository;
    @Mock PayoutHoldPolicy holdPolicy;
    @Mock StripeSplitGateway stripe;
    @Mock AuditService auditService;
    @Mock AdminAlertEscalator alerts;
    @Mock PlatformTransactionManager transactionManager;

    PaymentSplitService service;

    UUID bidId = UUID.randomUUID();
    UUID paymentId = UUID.randomUUID();
    UUID annId = UUID.randomUUID();
    UUID travelerId = UUID.randomUUID();
    UUID disputeId = UUID.randomUUID();
    UUID adminId = UUID.randomUUID();
    BidEntity bid;
    PaymentEntity payment;
    /** Ligne de partage « en base » : findById la relit, save la remplace. */
    Map<UUID, PaymentSplitEntity> splits = new HashMap<>();

    @BeforeEach
    void setUp() {
        service = new PaymentSplitService(paymentRepository, splitRepository, bidRepository, announcementRepository,
                userRepository, holdPolicy, stripe, auditService, alerts, transactionManager);
        bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", bidId);
        bid.setAnnouncementId(annId);
        bid.setPaymentMethod(PaymentMethod.STRIPE);
        bid.setCurrency("EUR");
        payment = new PaymentEntity();
        ReflectionTestUtils.setField(payment, "id", paymentId);
        payment.setBidId(bidId);
        payment.setStripePaymentIntentId("pi_1");
        payment.setStripeChargeId("ch_1");
        payment.setAmount(new BigDecimal("105.00"));
        payment.setCommissionAmount(new BigDecimal("5.00"));
        payment.setStatus(PaymentStatus.ESCROW);
        payment.setCurrency("EUR");
        lenient().when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        lenient().when(paymentRepository.findForBid(bidId)).thenReturn(Optional.of(payment));
        lenient().when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
        lenient().when(splitRepository.findByPaymentId(paymentId)).thenReturn(Optional.empty());
        AnnouncementEntity ann = new AnnouncementEntity();
        ann.setTravelerId(travelerId);
        lenient().when(announcementRepository.findById(annId)).thenReturn(Optional.of(ann));
        UserEntity traveler = new UserEntity();
        traveler.setStripeAccountId("acct_t");
        traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
        lenient().when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        lenient().when(holdPolicy.isHeld(travelerId)).thenReturn(false);
        lenient().when(splitRepository.save(any())).thenAnswer(inv -> {
            PaymentSplitEntity s = inv.getArgument(0);
            if (s.getId() == null) ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
            splits.put(s.getId(), s);
            return s;
        });
        lenient().when(splitRepository.findById(any())).thenAnswer(inv -> Optional.ofNullable(splits.get(inv.getArgument(0))));
    }

    private static String code(Throwable t) {
        return ((YadonyBusinessException) t).getErrorCode();
    }

    private void piStatus(String status) throws Exception {
        when(stripe.retrievePaymentIntent("pi_1"))
                .thenReturn(new StripeSplitGateway.PaymentIntentState(status, 10500L, 0L, "ch_1"));
    }

    private PaymentSplitEntity split(PaymentSplitMode mode, String refund, String payout, PaymentSplitStatus status) {
        PaymentSplitEntity s = new PaymentSplitEntity();
        ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
        s.setPaymentId(paymentId);
        s.setBidId(bidId);
        s.setDisputeId(disputeId);
        s.setTravelerId(travelerId);
        s.setSenderRefundAmount(new BigDecimal(refund));
        s.setTravelerPayoutAmount(new BigDecimal(payout));
        s.setCurrency("EUR");
        s.setMode(mode);
        s.setStatus(status);
        splits.put(s.getId(), s);
        return s;
    }

    // ── Validations ──

    @Test
    void plan_capture_modeRefundTransfer() throws Exception {
        piStatus("succeeded");
        var plan = service.plan(bidId, new BigDecimal("40.00"), new BigDecimal("60.00"));
        assertThat(plan.mode()).isEqualTo(PaymentSplitMode.REFUND_TRANSFER);
        assertThat(plan.netAvailable()).isEqualByComparingTo("100.00");
        assertThat(plan.travelerId()).isEqualTo(travelerId);
    }

    @Test
    void plan_nonCapture_modeCapturePartielle() throws Exception {
        piStatus("requires_capture");
        assertThat(service.plan(bidId, new BigDecimal("10"), new BigDecimal("10")).mode())
                .isEqualTo(PaymentSplitMode.PARTIAL_CAPTURE);
    }

    @Test
    void plan_etatPaymentIntentInattendu_422() throws Exception {
        piStatus("canceled");
        assertThatThrownBy(() -> service.plan(bidId, BigDecimal.ONE, BigDecimal.ONE))
                .satisfies(t -> assertThat(code(t)).isEqualTo("split-payment-intent-state"));
    }

    @Test
    void plan_stripeIndisponible_502() throws Exception {
        when(stripe.retrievePaymentIntent("pi_1")).thenThrow(new ApiConnectionException("down"));
        assertThatThrownBy(() -> service.plan(bidId, BigDecimal.ONE, BigDecimal.ONE))
                .satisfies(t -> assertThat(((YadonyBusinessException) t).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    /** Le garde-fou refunded_amount ne bloque plus : il réduit le net disponible. */
    @Test
    void plan_remboursementPartielAnterieur_calculeLeNetRestant() throws Exception {
        payment.setRefundedAmount(new BigDecimal("30.00"));
        assertThatThrownBy(() -> service.plan(bidId, new BigDecimal("40.00"), new BigDecimal("30.01")))
                .satisfies(t -> {
                    assertThat(code(t)).isEqualTo("split-exceeds-net");
                    assertThat(((YadonyBusinessException) t).getProperties().get("netAvailable")).isEqualTo("70.00");
                });
        piStatus("succeeded");
        assertThat(service.plan(bidId, new BigDecimal("40.00"), new BigDecimal("30.00")).netAvailable())
                .isEqualByComparingTo("70.00");
    }

    @Test
    void plan_sommeAuDelaDuNet_422() {
        assertThatThrownBy(() -> service.plan(bidId, new BigDecimal("50.01"), new BigDecimal("50.00")))
                .satisfies(t -> assertThat(code(t)).isEqualTo("split-exceeds-net"));
    }

    @Test
    void plan_montantsInvalides_422() {
        assertThatThrownBy(() -> service.plan(bidId, null, BigDecimal.ONE))
                .satisfies(t -> assertThat(code(t)).isEqualTo("split-amounts-required"));
        assertThatThrownBy(() -> service.plan(bidId, new BigDecimal("-1"), BigDecimal.ONE))
                .satisfies(t -> assertThat(code(t)).isEqualTo("split-amount-negative"));
        assertThatThrownBy(() -> service.plan(bidId, BigDecimal.ZERO, BigDecimal.ZERO))
                .satisfies(t -> assertThat(code(t)).isEqualTo("split-amount-zero"));
        assertThatThrownBy(() -> service.plan(bidId, new BigDecimal("1.001"), BigDecimal.ONE))
                .satisfies(t -> assertThat(code(t)).isEqualTo("split-amount-precision"));
    }

    @Test
    void plan_especes_422() {
        bid.setPaymentMethod(PaymentMethod.CASH);
        assertThatThrownBy(() -> service.plan(bidId, BigDecimal.ONE, BigDecimal.ONE))
                .satisfies(t -> assertThat(code(t)).isEqualTo("split-not-applicable-cash"));
    }

    @Test
    void plan_mobileMoney_bloque422() {
        payment.setRail(PaymentRail.PAWAPAY);
        assertThatThrownBy(() -> service.plan(bidId, BigDecimal.ONE, BigDecimal.ONE))
                .satisfies(t -> {
                    assertThat(code(t)).isEqualTo("split-mobile-money-unsupported");
                    assertThat(((YadonyBusinessException) t).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                });
    }

    @Test
    void plan_refusDuRailEtDeLEtat() {
        payment.setLegacyDestinationCharge(true);
        assertThatThrownBy(() -> service.plan(bidId, BigDecimal.ONE, BigDecimal.ONE))
                .satisfies(t -> assertThat(code(t)).isEqualTo("split-legacy-unsupported"));
        payment.setLegacyDestinationCharge(false);
        payment.setStatus(PaymentStatus.RELEASED);
        assertThatThrownBy(() -> service.plan(bidId, BigDecimal.ONE, BigDecimal.ONE))
                .satisfies(t -> assertThat(code(t)).isEqualTo("payment-not-in-escrow"));
        payment.setStatus(PaymentStatus.ESCROW);
        payment.setDisputed(true);
        assertThatThrownBy(() -> service.plan(bidId, BigDecimal.ONE, BigDecimal.ONE))
                .satisfies(t -> assertThat(code(t)).isEqualTo("payment-disputed"));
        payment.setDisputed(false);
        when(splitRepository.findByPaymentId(paymentId)).thenReturn(Optional.of(new PaymentSplitEntity()));
        assertThatThrownBy(() -> service.plan(bidId, BigDecimal.ONE, BigDecimal.ONE))
                .satisfies(t -> assertThat(code(t)).isEqualTo("split-already-exists"));
    }

    @Test
    void plan_sansPaiementNiBid_422() {
        when(paymentRepository.findForBid(bidId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.plan(bidId, BigDecimal.ONE, BigDecimal.ONE))
                .satisfies(t -> assertThat(code(t)).isEqualTo("split-no-payment"));
        assertThat(service.availability(null).reasonCode()).isEqualTo("split-no-bid");
    }

    @Test
    void plan_voyageurGeleOuSansCompte_partVoyageurRefusee() {
        when(holdPolicy.isHeld(travelerId)).thenReturn(true);
        assertThatThrownBy(() -> service.plan(bidId, BigDecimal.ONE, BigDecimal.ONE))
                .satisfies(t -> assertThat(code(t)).isEqualTo("payout-beneficiary-held"));
        when(holdPolicy.isHeld(travelerId)).thenReturn(false);
        userRepository.findById(travelerId).get().setStripeAccountStatus(StripeAccountStatus.DISABLED);
        assertThatThrownBy(() -> service.plan(bidId, BigDecimal.ONE, BigDecimal.ONE))
                .satisfies(t -> assertThat(code(t)).isEqualTo("traveler-no-connect"));
    }

    @Test
    void plan_partVoyageurNulle_neVerifiePasLeVoyageur() throws Exception {
        piStatus("succeeded");
        assertThat(service.plan(bidId, new BigDecimal("50"), BigDecimal.ZERO).travelerPayout()).isZero();
        verify(holdPolicy, never()).isHeld(any());
    }

    @Test
    void availability_exposeLeNetDisponible() {
        var a = service.availability(bidId);
        assertThat(a.splittable()).isTrue();
        assertThat(a.netAvailable()).isEqualByComparingTo("100.00");
        assertThat(a.rail()).isEqualTo("STRIPE");
        payment.setRefundedAmount(new BigDecimal("100.00"));
        assertThat(service.availability(bidId).reasonCode()).isEqualTo("split-nothing-available");
    }

    // ── Claim ──

    @Test
    void claim_partVoyageur_releasedEtRefundedAmountFinal() {
        payment.setRefundedAmount(new BigDecimal("5.00"));
        when(paymentRepository.claimForSplit(eq(paymentId), eq(PaymentStatus.RELEASED), any(), any())).thenReturn(1);
        var plan = new PaymentSplitService.SplitPlan(paymentId, bidId, travelerId, new BigDecimal("40.00"),
                new BigDecimal("50.00"), "EUR", PaymentSplitMode.REFUND_TRANSFER, new BigDecimal("95.00"));

        PaymentSplitEntity s = service.claim(plan, disputeId, adminId);

        verify(paymentRepository).claimForSplit(eq(paymentId), eq(PaymentStatus.RELEASED), any(),
                eq(new BigDecimal("45.00")));
        assertThat(s.getStatus()).isEqualTo(PaymentSplitStatus.CLAIMED);
        assertThat(s.getDecidedBy()).isEqualTo(adminId);
        verify(auditService).log(eq("PAYMENT"), eq(paymentId), eq("PAYMENT_SPLIT_DECIDED"), eq(adminId), any());
    }

    @Test
    void claim_sansPartVoyageur_refunded() {
        when(paymentRepository.claimForSplit(eq(paymentId), eq(PaymentStatus.REFUNDED), isNull(), any())).thenReturn(1);
        service.claim(new PaymentSplitService.SplitPlan(paymentId, bidId, travelerId, new BigDecimal("40"),
                BigDecimal.ZERO, "EUR", PaymentSplitMode.REFUND_TRANSFER, new BigDecimal("100")), disputeId, adminId);
        verify(paymentRepository).claimForSplit(eq(paymentId), eq(PaymentStatus.REFUNDED), isNull(),
                eq(new BigDecimal("40")));
    }

    @Test
    void claim_paiementSortiDuSequestre_409SansLigne() {
        when(paymentRepository.claimForSplit(any(), any(), any(), any())).thenReturn(0);
        assertThatThrownBy(() -> service.claim(new PaymentSplitService.SplitPlan(paymentId, bidId, travelerId,
                BigDecimal.ONE, BigDecimal.ONE, "EUR", PaymentSplitMode.REFUND_TRANSFER, BigDecimal.TEN), disputeId, adminId))
                .satisfies(t -> assertThat(((YadonyBusinessException) t).getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verify(splitRepository, never()).save(any());
    }

    // ── Exécution ──

    @Test
    void execute_capture_refundPartielPuisTransferPartiel() throws Exception {
        PaymentSplitEntity s = split(PaymentSplitMode.REFUND_TRANSFER, "40.00", "55.50", PaymentSplitStatus.CLAIMED);
        when(stripe.findRefund("pi_1", s.getId().toString())).thenReturn(Optional.empty());
        when(stripe.createRefund(eq("pi_1"), eq(4000L), any(), eq("split-refund-" + s.getId()))).thenReturn("re_1");
        when(stripe.findTransfer("split-" + s.getId())).thenReturn(Optional.empty());
        when(stripe.createTransfer(eq(5550L), eq("eur"), eq("acct_t"), eq("ch_1"), eq("split-" + s.getId()), any(),
                eq("split-transfer-" + s.getId()))).thenReturn("tr_1");

        PaymentSplitEntity done = service.execute(s.getId());

        assertThat(done.getStatus()).isEqualTo(PaymentSplitStatus.COMPLETED);
        assertThat(done.getStripeRefundId()).isEqualTo("re_1");
        assertThat(done.getStripeTransferId()).isEqualTo("tr_1");
        assertThat(done.getCompletedAt()).isNotNull();
        verify(auditService).log(eq("PAYMENT"), eq(paymentId), eq("PAYMENT_SPLIT_COMPLETED"), any(), any());
        verifyNoInteractions(alerts);
    }

    @Test
    void execute_nonCapture_capturePartiellePuisTransfer() throws Exception {
        PaymentSplitEntity s = split(PaymentSplitMode.PARTIAL_CAPTURE, "40.00", "60.00", PaymentSplitStatus.CLAIMED);
        when(stripe.retrievePaymentIntent("pi_1"))
                .thenReturn(new StripeSplitGateway.PaymentIntentState("requires_capture", 10500L, 0L, null));
        when(stripe.capture("pi_1", 6500L, "split-capture-" + s.getId()))
                .thenReturn(new StripeSplitGateway.PaymentIntentState("succeeded", 10500L, 6500L, "ch_cap"));
        when(stripe.findTransfer(anyString())).thenReturn(Optional.empty());
        when(stripe.createTransfer(eq(6000L), eq("eur"), eq("acct_t"), eq("ch_cap"), anyString(), any(), anyString()))
                .thenReturn("tr_2");

        PaymentSplitEntity done = service.execute(s.getId());

        assertThat(done.getStatus()).isEqualTo(PaymentSplitStatus.COMPLETED);
        assertThat(done.isStripeCaptureDone()).isTrue();
        verify(stripe, never()).createRefund(anyString(), anyLong(), any(), anyString());
    }

    @Test
    void execute_capturePartielleIncoherente_resteClaimed() throws Exception {
        PaymentSplitEntity s = split(PaymentSplitMode.PARTIAL_CAPTURE, "40.00", "60.00", PaymentSplitStatus.CLAIMED);
        when(stripe.retrievePaymentIntent("pi_1"))
                .thenReturn(new StripeSplitGateway.PaymentIntentState("succeeded", 10500L, 10500L, "ch_1"));

        PaymentSplitEntity r = service.execute(s.getId());

        assertThat(r.getStatus()).isEqualTo(PaymentSplitStatus.CLAIMED);
        assertThat(r.getLastError()).contains("Capture partielle incohérente");
        verify(stripe, never()).capture(anyString(), anyLong(), anyString());
    }

    /** Échec entre le remboursement et le transfert : état intermédiaire traçable, puis reprise. */
    @Test
    void execute_echecDuTransfert_puisReprise_sansSecondRemboursement() throws Exception {
        PaymentSplitEntity s = split(PaymentSplitMode.REFUND_TRANSFER, "40.00", "60.00", PaymentSplitStatus.CLAIMED);
        when(stripe.findRefund(anyString(), anyString())).thenReturn(Optional.empty());
        when(stripe.createRefund(anyString(), anyLong(), any(), anyString())).thenReturn("re_1");
        when(stripe.findTransfer(anyString())).thenReturn(Optional.empty());
        when(stripe.createTransfer(anyLong(), anyString(), anyString(), any(), anyString(), any(), anyString()))
                .thenThrow(new ApiConnectionException("timeout"))
                .thenReturn("tr_ok");

        PaymentSplitEntity first = service.execute(s.getId());

        assertThat(first.getStatus()).isEqualTo(PaymentSplitStatus.SENDER_REFUNDED);
        assertThat(first.getStripeRefundId()).isEqualTo("re_1");
        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(first.getLastError()).contains("timeout");
        verify(alerts).raiseOnce(eq("PAYMENT_SPLIT_STALLED_" + paymentId), anyString(), any());

        PaymentSplitEntity second = service.execute(s.getId());

        assertThat(second.getStatus()).isEqualTo(PaymentSplitStatus.COMPLETED);
        assertThat(second.getLastError()).isNull();
        verify(stripe, times(1)).createRefund(anyString(), anyLong(), any(), anyString());
        verify(stripe, times(1)).findRefund(anyString(), anyString());
    }

    /** Crash après un refund réussi chez Stripe mais non commité : la reprise le retrouve. */
    @Test
    void execute_refundDejaCreeChezStripe_retrouveSansRecreer() throws Exception {
        PaymentSplitEntity s = split(PaymentSplitMode.REFUND_TRANSFER, "40.00", "60.00", PaymentSplitStatus.CLAIMED);
        when(stripe.findRefund("pi_1", s.getId().toString())).thenReturn(Optional.of("re_existing"));
        when(stripe.findTransfer("split-" + s.getId())).thenReturn(Optional.of("tr_existing"));

        PaymentSplitEntity done = service.execute(s.getId());

        assertThat(done.getStatus()).isEqualTo(PaymentSplitStatus.COMPLETED);
        assertThat(done.getStripeRefundId()).isEqualTo("re_existing");
        assertThat(done.getStripeTransferId()).isEqualTo("tr_existing");
        verify(stripe, never()).createRefund(anyString(), anyLong(), any(), anyString());
        verify(stripe, never()).createTransfer(anyLong(), anyString(), anyString(), any(), anyString(), any(), anyString());
    }

    @Test
    void execute_partageTermine_noOp() {
        PaymentSplitEntity s = split(PaymentSplitMode.REFUND_TRANSFER, "1", "1", PaymentSplitStatus.COMPLETED);
        assertThat(service.execute(s.getId())).isSameAs(s);
        verifyNoInteractions(stripe);
    }

    @Test
    void execute_partsNulles_aucunAppelDeCreation() throws Exception {
        PaymentSplitEntity s = split(PaymentSplitMode.REFUND_TRANSFER, "0", "30.00", PaymentSplitStatus.CLAIMED);
        when(stripe.findTransfer(anyString())).thenReturn(Optional.empty());
        when(stripe.createTransfer(eq(3000L), anyString(), anyString(), any(), anyString(), any(), anyString()))
                .thenReturn("tr");
        assertThat(service.execute(s.getId()).getStatus()).isEqualTo(PaymentSplitStatus.COMPLETED);
        verify(stripe, never()).findRefund(anyString(), anyString());

        PaymentSplitEntity s2 = split(PaymentSplitMode.REFUND_TRANSFER, "30.00", "0", PaymentSplitStatus.SENDER_REFUNDED);
        assertThat(service.execute(s2.getId()).getStatus()).isEqualTo(PaymentSplitStatus.COMPLETED);
        verify(stripe, never()).findTransfer("split-" + s2.getId());
    }

    @Test
    void execute_voyageurSansCompteAuTransfert_echecTrace() throws Exception {
        PaymentSplitEntity s = split(PaymentSplitMode.REFUND_TRANSFER, "0", "30.00", PaymentSplitStatus.SENDER_REFUNDED);
        when(stripe.findTransfer(anyString())).thenReturn(Optional.empty());
        userRepository.findById(travelerId).get().setStripeAccountId(null);

        PaymentSplitEntity r = service.execute(s.getId());

        assertThat(r.getStatus()).isEqualTo(PaymentSplitStatus.SENDER_REFUNDED);
        assertThat(r.getLastError()).contains("Connect");
    }

    @Test
    void execute_sansChargeConnue_laRelitSurLePaymentIntent() throws Exception {
        payment.setStripeChargeId(null);
        PaymentSplitEntity s = split(PaymentSplitMode.REFUND_TRANSFER, "0", "30.00", PaymentSplitStatus.SENDER_REFUNDED);
        when(stripe.findTransfer(anyString())).thenReturn(Optional.empty());
        when(stripe.retrievePaymentIntent("pi_1"))
                .thenReturn(new StripeSplitGateway.PaymentIntentState("succeeded", 10500L, 10500L, "ch_pi"));
        when(stripe.createTransfer(anyLong(), anyString(), anyString(), eq("ch_pi"), anyString(), any(), anyString()))
                .thenReturn("tr");
        assertThat(service.execute(s.getId()).getStatus()).isEqualTo(PaymentSplitStatus.COMPLETED);
    }

    @Test
    void findForDispute_delegue() {
        when(splitRepository.findByDisputeId(disputeId)).thenReturn(Optional.empty());
        assertThat(service.findForDispute(disputeId)).isEmpty();
    }
}
