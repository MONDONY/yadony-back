package com.yadony.api.admin;

import com.stripe.exception.IdempotencyException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Transfer;
import com.stripe.net.RequestOptions;
import com.stripe.param.TransferCreateParams;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.admin.dto.AdminPaymentDetailResponse;
import com.yadony.api.admin.dto.AdminPaymentListItemResponse;
import com.yadony.api.admin.dto.PayoutReleaseRequest;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.RefundProcessor;
import com.yadony.api.payments.chargeback.ChargebackRepository;
import com.yadony.api.payments.hold.PayoutHoldPolicy;
import com.yadony.api.payments.hold.PayoutHoldReason;
import com.yadony.api.payments.hold.PayoutHoldStatus;
import com.yadony.api.payments.mobilemoney.MobileMoneyPayoutInitiator;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Force-release et relance mobile money face a un beneficiaire gele, a un litige, a un compte
 * Stripe inutilisable, et a la derogation motivee de l'administrateur.
 */
@ExtendWith(MockitoExtension.class)
class AdminPaymentControllerHoldTest {

    /** Capture déjà faite par défaut : PaymentIntent succeeded, aucun charge id renvoyé. */
    private com.yadony.api.payments.EscrowCaptureService escrowCapture = org.mockito.Mockito.mock(com.yadony.api.payments.EscrowCaptureService.class, invocation -> new com.yadony.api.payments.EscrowCaptureService.Outcome(null, false));

    @Mock PaymentRepository paymentRepository;
    @Mock AdminAlertRepository adminAlertRepository;
    @Mock AuditService auditService;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock UserRepository userRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock ChargebackRepository chargebackRepository;
    @Mock MobileMoneyPayoutInitiator payoutInitiator;
    @Mock PawapayOperationService pawapayOperations;
    @Mock PawapaySubmissionService pawapaySubmission;
    @Mock RefundProcessor refundProcessor;
    @Mock EntityManager entityManager;
    @Mock PlatformTransactionManager transactionManager;
    @Mock PayoutHoldPolicy holdPolicy;
    @Mock AdminPaymentInsights insights;
    @Mock AdminPaymentTimeline timeline;

    private AdminPaymentController controller;
    private PaymentEntity payment;
    private BidEntity bid;
    private UserEntity traveler;
    private final UUID travelerId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private static final String GOOD_REASON = "Enquete close, fraude non etablie";

    @BeforeEach
    void setUp() {
        controller = new AdminPaymentController(paymentRepository, adminAlertRepository, auditService,
                bidRepository, announcementRepository, userRepository, eventPublisher, chargebackRepository,
                payoutInitiator, pawapayOperations, pawapaySubmission, refundProcessor, entityManager,
                transactionManager, holdPolicy, insights, timeline, escrowCapture);
        AnnouncementEntity a = new AnnouncementEntity();
        ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
        a.setTravelerId(travelerId);
        bid = new BidEntity();
        ReflectionTestUtils.setField(bid, "id", UUID.randomUUID());
        bid.setAnnouncementId(a.getId());
        bid.setSenderId(UUID.randomUUID());
        bid.setStatus(BidStatus.ACCEPTED);
        payment = new PaymentEntity();
        ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
        payment.setBidId(bid.getId());
        payment.setStripePaymentIntentId("pi_hold");
        payment.setStripeChargeId("ch_hold");
        payment.setStatus(PaymentStatus.ESCROW);
        payment.setAmount(new BigDecimal("100.00"));
        payment.setCommissionAmount(new BigDecimal("12.00"));
        traveler = new UserEntity();
        ReflectionTestUtils.setField(traveler, "id", travelerId);
        traveler.setStripeAccountId("acct_hold");
        traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
        lenient().when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        lenient().when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        lenient().when(announcementRepository.findById(a.getId())).thenReturn(Optional.of(a));
        lenient().when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        AdminPrincipal principal = new AdminPrincipal(adminId, "admin@yadony.test", AdminRole.ADMIN, false, "uid-admin");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private void held() {
        lenient().when(holdPolicy.isHeld(travelerId)).thenReturn(true);
        lenient().when(holdPolicy.statusOf(travelerId)).thenReturn(
                new PayoutHoldStatus(LocalDateTime.now().minusDays(1), List.of(PayoutHoldReason.BANNED)));
    }

    private static String code(Throwable e) {
        return ((YadonyBusinessException) e).getErrorCode();
    }

    private static HttpStatus status(Throwable e) {
        return ((YadonyBusinessException) e).getStatus();
    }

    /**
     * PaymentIntent déjà capturé : la capture passe par EscrowCaptureService (simulé « déjà
     * capturé » dans ce test), le contrôleur n'appelle plus Stripe en direct pour le PI.
     */
    private MockedStatic<PaymentIntent> succeededPi() {
        return mockStatic(PaymentIntent.class);
    }

    // ── force-release ─────────────────────────────────────────────────────────────────────────

    @Test
    void forceRelease_beneficiaireGele_sansCorps_409_sansClaimNiStripe() {
        held();
        try (MockedStatic<Transfer> tr = mockStatic(Transfer.class)) {
            assertThatThrownBy(() -> controller.forceRelease(payment.getId(), null))
                    .satisfies(e -> {
                        assertThat(status(e)).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(code(e)).isEqualTo("payout-beneficiary-held");
                        assertThat(((YadonyBusinessException) e).getProperties())
                                .containsEntry("holdReasons", List.of("BANNED"))
                                .containsEntry("blockers", List.of("BENEFICIARY_HELD"));
                    });
            tr.verifyNoInteractions();
        }
        verify(paymentRepository, never()).markReleasedIfEscrow(any(), any());
    }

    @Test
    void forceRelease_paiementDejaLibere_beneficiaireGele_422CommeAvant() {
        held();
        payment.setStatus(PaymentStatus.RELEASED);
        assertThatThrownBy(() -> controller.forceRelease(payment.getId(), null))
                .satisfies(e -> assertThat(code(e)).isEqualTo("payment-not-in-escrow"));
        verify(paymentRepository, never()).markReleasedIfEscrow(any(), any());
    }

    @Test
    void forceRelease_paiementDispute_sansDerogation_409() {
        payment.setDisputed(true);
        held();
        assertThatThrownBy(() -> controller.forceRelease(payment.getId(), new PayoutReleaseRequest(false, GOOD_REASON)))
                .satisfies(e -> {
                    assertThat(status(e)).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(code(e)).isEqualTo("payment-disputed");
                    assertThat(((YadonyBusinessException) e).getProperties())
                            .containsEntry("blockers", List.of("DISPUTED", "BENEFICIARY_HELD"));
                });
        verify(paymentRepository, never()).markReleasedIfEscrow(any(), any());
    }

    @Test
    void forceRelease_derogationMotivee_verse_etAuditeLAdministrateur() {
        held();
        payment.setDisputed(true);
        when(paymentRepository.markReleasedIfEscrow(eq(payment.getId()), any())).thenReturn(1);

        try (MockedStatic<PaymentIntent> pi = succeededPi();
             MockedStatic<Transfer> tr = mockStatic(Transfer.class)) {
            ArgumentCaptor<RequestOptions> opts = ArgumentCaptor.forClass(RequestOptions.class);
            tr.when(() -> Transfer.create(any(TransferCreateParams.class), opts.capture())).thenReturn(mock(Transfer.class));

            var resp = controller.forceRelease(payment.getId(), new PayoutReleaseRequest(true, "  " + GOOD_REASON + "  "));

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(opts.getValue().getIdempotencyKey()).isEqualTo("transfer-" + payment.getId());
        }
        verify(auditService).log(eq("PAYMENT"), eq(payment.getId()), eq("PAYOUT_HOLD_OVERRIDDEN_BY_ADMIN"), eq(adminId),
                eq(Map.of("paymentId", payment.getId().toString(), "travelerId", travelerId.toString(),
                        "blockers", "DISPUTED,BENEFICIARY_HELD", "holdReasons", "BANNED",
                        "overrideReason", GOOD_REASON, "source", "admin-force-release")));
    }

    @Test
    void forceRelease_motifDeDerogationTropCourt_422_sansClaim() {
        held();
        assertThatThrownBy(() -> controller.forceRelease(payment.getId(), new PayoutReleaseRequest(true, "court")))
                .satisfies(e -> {
                    assertThat(status(e)).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(code(e)).isEqualTo("override-reason-invalid");
                });
        assertThatThrownBy(() -> controller.forceRelease(payment.getId(), new PayoutReleaseRequest(true, "x".repeat(501))))
                .satisfies(e -> assertThat(code(e)).isEqualTo("override-reason-invalid"));
        assertThatThrownBy(() -> controller.forceRelease(payment.getId(), new PayoutReleaseRequest(true, null)))
                .satisfies(e -> assertThat(code(e)).isEqualTo("override-reason-invalid"));
        verify(paymentRepository, never()).markReleasedIfEscrow(any(), any());
    }

    @Test
    void forceRelease_nonGele_sansCorps_marcheCommeAvant_avecCleDIdempotence() {
        when(paymentRepository.markReleasedIfEscrow(eq(payment.getId()), any())).thenReturn(1);
        try (MockedStatic<PaymentIntent> pi = succeededPi();
             MockedStatic<Transfer> tr = mockStatic(Transfer.class)) {
            ArgumentCaptor<RequestOptions> opts = ArgumentCaptor.forClass(RequestOptions.class);
            tr.when(() -> Transfer.create(any(TransferCreateParams.class), opts.capture())).thenReturn(mock(Transfer.class));

            controller.forceRelease(payment.getId(), null);

            // Meme cle que la livraison (DeliveryEventListener#releaseV2) : un double
            // declenchement ne paie pas deux fois.
            assertThat(opts.getValue().getIdempotencyKey()).isEqualTo("transfer-" + payment.getId());
        }
        verify(auditService, never()).log(any(), any(), eq("PAYOUT_HOLD_OVERRIDDEN_BY_ADMIN"), any(), anyMap());
    }

    @Test
    void forceRelease_compteStripeDesactive_409_sansClaim() {
        traveler.setStripeAccountStatus(StripeAccountStatus.DISABLED);
        try (MockedStatic<Transfer> tr = mockStatic(Transfer.class)) {
            assertThatThrownBy(() -> controller.forceRelease(payment.getId(), null))
                    .satisfies(e -> {
                        assertThat(status(e)).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(code(e)).isEqualTo("stripe-account-unusable");
                    });
            tr.verifyNoInteractions();
        }
        verify(paymentRepository, never()).markReleasedIfEscrow(any(), any());
        verify(auditService).log(eq("PAYMENT"), eq(payment.getId()), eq("PAYOUT_BLOCKED_STRIPE_ACCOUNT_UNUSABLE"),
                eq(adminId), anyMap());
    }

    @Test
    void forceRelease_cleDIdempotenceDejaUtiliseeAutrementParLaLivraison_409() {
        when(paymentRepository.markReleasedIfEscrow(eq(payment.getId()), any())).thenReturn(1);
        try (MockedStatic<PaymentIntent> pi = succeededPi();
             MockedStatic<Transfer> tr = mockStatic(Transfer.class)) {
            tr.when(() -> Transfer.create(any(TransferCreateParams.class), any(RequestOptions.class)))
                    .thenThrow(mock(IdempotencyException.class));

            assertThatThrownBy(() -> controller.forceRelease(payment.getId(), null))
                    .satisfies(e -> {
                        assertThat(status(e)).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(code(e)).isEqualTo("transfer-already-attempted");
                    });
        }
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void forceRelease_mobileMoney_gele_avecDerogation_passeLaDerogationALInitiateur() {
        payment.setRail(PaymentRail.PAWAPAY);
        payment.setCurrency("XOF");
        payment.setAmount(new BigDecimal("16800"));
        payment.setCommissionAmount(new BigDecimal("1800"));
        held();
        when(paymentRepository.markReleasedIfEscrow(eq(payment.getId()), any())).thenReturn(1);
        PawapayOperationEntity op = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.PAYOUT,
                payment.getId(), null, new BigDecimal("15000"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        when(payoutInitiator.release(eq(payment), eq(bid.getId()), eq(travelerId), eq(new BigDecimal("15000")),
                eq("admin-force-release"), eq(true))).thenReturn(op);

        controller.forceRelease(payment.getId(), new PayoutReleaseRequest(true, GOOD_REASON));

        verify(payoutInitiator).release(any(), any(), any(), any(), eq("admin-force-release"), eq(true));
    }

    @Test
    void forceRelease_mobileMoney_gele_sansDerogation_409() {
        payment.setRail(PaymentRail.PAWAPAY);
        held();
        assertThatThrownBy(() -> controller.forceRelease(payment.getId(), null))
                .satisfies(e -> assertThat(code(e)).isEqualTo("payout-beneficiary-held"));
        verify(payoutInitiator, never()).release(any(), any(), any(), any(), any());
        verify(payoutInitiator, never()).release(any(), any(), any(), any(), any(), eq(true));
    }

    // ── retry-payout ──────────────────────────────────────────────────────────────────────────

    private void releasedMobileMoneyWithDeadPayout() {
        payment.setRail(PaymentRail.PAWAPAY);
        payment.setStatus(PaymentStatus.RELEASED);
        payment.setCurrency("XOF");
        PawapayOperationEntity dead = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.PAYOUT,
                payment.getId(), null, new BigDecimal("15000"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        dead.setStatus(PawapayOperationStatus.FAILED);
        lenient().when(pawapayOperations.findLatest(payment.getId(), PawapayOperationKind.PAYOUT)).thenReturn(Optional.of(dead));
    }

    @Test
    void retryPayout_beneficiaireGele_sansCorps_409() {
        releasedMobileMoneyWithDeadPayout();
        held();
        assertThatThrownBy(() -> controller.retryMobileMoneyPayout(payment.getId(), null))
                .satisfies(e -> {
                    assertThat(status(e)).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(code(e)).isEqualTo("payout-beneficiary-held");
                });
        verify(payoutInitiator, never()).release(any(), any(), any(), any(), any());
    }

    @Test
    void retryPayout_paiementDispute_sansCorps_409() {
        releasedMobileMoneyWithDeadPayout();
        payment.setDisputed(true);
        assertThatThrownBy(() -> controller.retryMobileMoneyPayout(payment.getId(), null))
                .satisfies(e -> assertThat(code(e)).isEqualTo("payment-disputed"));
    }

    @Test
    void retryPayout_gele_avecDerogation_relanceEtAudite() {
        releasedMobileMoneyWithDeadPayout();
        held();
        PawapayOperationEntity op = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.PAYOUT,
                payment.getId(), null, new BigDecimal("15000"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        when(payoutInitiator.release(eq(payment), eq(bid.getId()), eq(travelerId), eq(new BigDecimal("15000")),
                eq("admin-retry"), eq(true))).thenReturn(op);

        controller.retryMobileMoneyPayout(payment.getId(), new PayoutReleaseRequest(true, GOOD_REASON));

        verify(auditService).log(eq("PAYMENT"), eq(payment.getId()), eq("PAYOUT_HOLD_OVERRIDDEN_BY_ADMIN"), eq(adminId),
                eq(Map.of("paymentId", payment.getId().toString(), "travelerId", travelerId.toString(),
                        "blockers", "BENEFICIARY_HELD", "holdReasons", "BANNED",
                        "overrideReason", GOOD_REASON, "source", "admin-retry")));
    }

    @Test
    void retryPayout_nonGele_sansCorps_marcheCommeAvant() {
        releasedMobileMoneyWithDeadPayout();
        PawapayOperationEntity op = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.PAYOUT,
                payment.getId(), null, new BigDecimal("15000"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        when(payoutInitiator.release(eq(payment), eq(bid.getId()), eq(travelerId), eq(new BigDecimal("15000")),
                eq("admin-retry"))).thenReturn(op);

        controller.retryMobileMoneyPayout(payment.getId(), null);

        verify(payoutInitiator).release(any(), any(), any(), any(), eq("admin-retry"));
    }

    // ── lecture ───────────────────────────────────────────────────────────────────────────────

    @Test
    void list_heldTrue_filtreEtExposeLeGelDuBeneficiaire() {
        LocalDateTime heldAt = LocalDateTime.now().minusHours(2);
        payment.setPayoutHeldAt(heldAt);
        when(insights.search(org.mockito.ArgumentMatchers.argThat(f -> f != null && f.held()), any()))
                .thenReturn(new PageImpl<>(List.of(payment)));
        when(paymentRepository.findBeneficiaries(List.of(payment.getId())))
                .thenReturn(List.<Object[]>of(new Object[]{payment.getId(), travelerId}));
        when(holdPolicy.statusesOf(List.of(travelerId))).thenReturn(Map.of(travelerId,
                new PayoutHoldStatus(heldAt, List.of(PayoutHoldReason.KYC_REVOKED))));

        var body = controller.list(null, null, null, null, null, true, null, null, 0, 20).getBody();

        AdminPaymentListItemResponse item = body.getContent().get(0);
        assertThat(item.payoutHeldAt()).isEqualTo(heldAt);
        assertThat(item.beneficiaryHeld()).isTrue();
        assertThat(item.beneficiaryHoldReason()).isEqualTo("KYC_REVOKED");
        assertThat(item.travelerId()).isEqualTo(travelerId);
    }

    @Test
    void list_sansFiltreHeld_beneficiaireNonGele() {
        when(insights.search(org.mockito.ArgumentMatchers.argThat(f -> f != null && !f.held()), any()))
                .thenReturn(new PageImpl<>(List.of(payment)));
        when(paymentRepository.findBeneficiaries(List.of(payment.getId())))
                .thenReturn(List.<Object[]>of(new Object[]{payment.getId(), travelerId}));
        when(holdPolicy.statusesOf(List.of(travelerId))).thenReturn(Map.of());

        AdminPaymentListItemResponse item = controller.list(null, null, null, null, null, null, null, null, 0, 20)
                .getBody().getContent().get(0);

        assertThat(item.beneficiaryHeld()).isFalse();
        assertThat(item.beneficiaryHoldReason()).isNull();
        assertThat(item.payoutHeldAt()).isNull();
    }

    @Test
    void detail_exposeLeGelDuBeneficiaire() {
        held();
        LocalDateTime heldAt = LocalDateTime.now();
        payment.setPayoutHeldAt(heldAt);

        AdminPaymentDetailResponse d = controller.getById(payment.getId()).getBody();

        assertThat(d.payoutHeldAt()).isEqualTo(heldAt);
        assertThat(d.beneficiaryHeld()).isTrue();
        assertThat(d.beneficiaryHoldReason()).isEqualTo("BANNED");
        assertThat(d.travelerId()).isEqualTo(travelerId);
    }

    // ── remboursements : jamais retenus ───────────────────────────────────────────────────────

    /** Rembourser l'expediteur d'un voyageur gele reste possible : seuls les GAINS sont retenus. */
    @Test
    void remboursementExpediteur_dUnBeneficiaireGele_nEstPasBloque() {
        held();
        payment.setDisputed(false);
        when(paymentRepository.markRefundedIfEscrow(payment.getId())).thenReturn(1);
        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<com.stripe.model.Refund> refund = mockStatic(com.stripe.model.Refund.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getStatus()).thenReturn("succeeded");
            piStatic.when(() -> PaymentIntent.retrieve("pi_hold")).thenReturn(pi);

            var resp = controller.refund(payment.getId());

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            refund.verify(() -> com.stripe.model.Refund.create(any(com.stripe.param.RefundCreateParams.class)));
        }
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    /**
     * Garde-fou structurel : les flux de remboursement (expediteur, wallet, commission) ne
     * consultent jamais le gel. Une dependance ajoutee par erreur ferait echouer ce test.
     */
    @Test
    void fluxDeRemboursement_neDependentPasDuGel() {
        for (Class<?> refundFlow : List.of(RefundProcessor.class,
                com.yadony.api.payments.wallet.WalletSelfRefundService.class,
                com.yadony.api.payments.wallet.WalletRefundRequestService.class,
                com.yadony.api.payments.cash.CashCommissionService.class)) {
            for (java.lang.reflect.Constructor<?> ctor : refundFlow.getDeclaredConstructors()) {
                assertThat(ctor.getParameterTypes())
                        .as("dependances de %s", refundFlow.getSimpleName())
                        .doesNotContain(PayoutHoldPolicy.class, com.yadony.api.payments.hold.PayoutHoldService.class);
            }
            assertThat(java.util.Arrays.stream(refundFlow.getDeclaredFields()).map(java.lang.reflect.Field::getType))
                    .doesNotContain(PayoutHoldPolicy.class, com.yadony.api.payments.hold.PayoutHoldService.class);
        }
    }
}
