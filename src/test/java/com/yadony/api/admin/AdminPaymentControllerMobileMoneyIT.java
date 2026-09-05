package com.yadony.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
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
import com.yadony.api.payments.mobilemoney.MobileMoneyPayoutInitiator;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tâche 18 — force-release, relances et remboursement mobile money de
 * {@link AdminPaymentController}.
 *
 * <p>Montage d'authentification admin repris de {@code AdminFinanceControllerIT} : un
 * {@link AdminPrincipal} authentifié directement via {@code authentication(...)}, sans passer
 * par {@code FirebaseTokenFilter}/{@code AdminAuthService}.
 *
 * <p><b>Ronde 1 (revue) — {@code entityManager} et {@code refundProcessor} mockés.</b>
 * {@code entityManager.refresh(payment)} (point 1) exige une entité RÉELLEMENT gérée par CET
 * EntityManager — ici {@code payment} est un simple POJO renvoyé par le mock
 * {@code paymentRepository}, jamais chargé via JPA : un vrai {@code EntityManager} lèverait
 * {@code IllegalArgumentException("entity not managed")}. Le mock en fait un no-op, comme pour
 * {@code RefundProcessorMobileMoneyTest} (tâche 17) qui documente la même limite pour
 * {@code attachRefundId} : les assertions qui suivent un refresh/attach portent donc sur les
 * APPELS (verify), jamais sur les champs de {@code payment} qu'un mock ne peut pas faire
 * évoluer — la preuve que la colonne survit réellement au flush vit dans
 * {@code PaymentRepositoryMobileMoneyTest#markReleasedIfEscrow_thenAttachPayoutId_thenRefresh_generatesNoUpdate}
 * (base H2 réelle).
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminPaymentControllerMobileMoneyIT {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean PaymentRepository paymentRepository;
    @MockitoBean BidRepository bidRepository;
    @MockitoBean AnnouncementRepository announcementRepository;
    @MockitoBean UserRepository userRepository;
    @MockitoBean MobileMoneyPayoutInitiator payoutInitiator;
    @MockitoBean PawapayOperationService operations;
    @MockitoBean PawapaySubmissionService submission;
    @MockitoBean EntityManager entityManager;
    @MockitoBean RefundProcessor refundProcessor;

    private PaymentEntity payment;
    private BidEntity bid;
    private final UUID travelerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
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
        payment.setRail(PaymentRail.PAWAPAY);
        payment.setStatus(PaymentStatus.ESCROW);
        payment.setAmount(new BigDecimal("16800"));
        payment.setCommissionAmount(new BigDecimal("1800"));
        payment.setCurrency("XOF");
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(bidRepository.findById(bid.getId())).thenReturn(Optional.of(bid));
        when(announcementRepository.findById(a.getId())).thenReturn(Optional.of(a));
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(new UserEntity()));
    }

    private static UsernamePasswordAuthenticationToken releaseAdmin(UUID adminId) {
        AdminPrincipal principal = new AdminPrincipal(adminId, "admin@yadony.test", AdminRole.ADMIN, false, "uid-admin-mm");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("PAYMENT_RELEASE")));
    }

    private static UsernamePasswordAuthenticationToken releaseAdmin() {
        return releaseAdmin(UUID.randomUUID());
    }

    private static UsernamePasswordAuthenticationToken refundAdmin() {
        AdminPrincipal principal = new AdminPrincipal(UUID.randomUUID(), "refund-admin@yadony.test", AdminRole.ADMIN, false, "uid-admin-refund");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("PAYMENT_REFUND")));
    }

    private static UsernamePasswordAuthenticationToken viewAdmin() {
        AdminPrincipal principal = new AdminPrincipal(UUID.randomUUID(), "view-admin@yadony.test", AdminRole.ADMIN, false, "uid-admin-view");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("PAYMENT_VIEW")));
    }

    private PawapayOperationEntity payout(PawapayOperationStatus status) {
        return payout(status, new BigDecimal("15000"));
    }

    private PawapayOperationEntity payout(PawapayOperationStatus status, BigDecimal amount) {
        PawapayOperationEntity o = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.PAYOUT, payment.getId(), null,
                amount, "XOF", "ORANGE_SEN", "SN", "221771234567");
        o.setStatus(status);
        return o;
    }

    // ── force-release ────────────────────────────────────────────────────────────────────────

    @Test
    void forceRelease_pawapay_usesInitiator_notStripe() throws Exception {
        UUID adminId = UUID.randomUUID();
        when(paymentRepository.markReleasedIfEscrow(eq(payment.getId()), any())).thenReturn(1);
        when(payoutInitiator.release(eq(payment), eq(bid.getId()), eq(travelerId), eq(new BigDecimal("15000")), eq("admin-force-release")))
                .thenReturn(payout(PawapayOperationStatus.ACCEPTED));

        mockMvc.perform(post("/admin/payments/{id}/force-release", payment.getId()).with(authentication(releaseAdmin(adminId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rail").value("PAWAPAY"));

        // Le claim atomique précède TOUJOURS l'appel pawaPay — jamais l'inverse.
        InOrder inOrder = inOrder(paymentRepository, payoutInitiator);
        inOrder.verify(paymentRepository).markReleasedIfEscrow(eq(payment.getId()), any());
        inOrder.verify(payoutInitiator).release(any(), any(), any(), any(), eq("admin-force-release"));
        verify(entityManager).refresh(payment);
    }

    @Test
    void forceRelease_pawapay_initiatorFailure_is422_andRollsBack() throws Exception {
        when(paymentRepository.markReleasedIfEscrow(eq(payment.getId()), any())).thenReturn(1);
        when(payoutInitiator.release(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("INSUFFICIENT_BALANCE"));

        mockMvc.perform(post("/admin/payments/{id}/force-release", payment.getId()).with(authentication(releaseAdmin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("mobile-money-payout-failed"));
        verify(entityManager, never()).refresh(any());
    }

    /**
     * Ronde 1, point 3 : l'acteur audité est l'ADMINISTRATEUR qui agit, jamais {@code bidId} ni
     * {@code null}. Vérifié en base réelle (H2 du profil test) — {@code auditService}/
     * {@code AuditLogRepository} ne sont pas mockés ici, et l'audit part dans sa propre
     * transaction REQUIRES_NEW déjà commitée par le temps que la requête HTTP répond.
     */
    @Test
    void forceRelease_pawapay_audits_actingAdmin_notBidId() throws Exception {
        UUID adminId = UUID.randomUUID();
        when(paymentRepository.markReleasedIfEscrow(eq(payment.getId()), any())).thenReturn(1);
        when(payoutInitiator.release(any(), any(), any(), any(), any())).thenReturn(payout(PawapayOperationStatus.ACCEPTED));

        mockMvc.perform(post("/admin/payments/{id}/force-release", payment.getId()).with(authentication(releaseAdmin(adminId))))
                .andExpect(status().isOk());

        UUID actorId = jdbc.queryForObject(
                "SELECT actor_id FROM audit_log WHERE entity_id = ? AND action = ? ORDER BY id DESC LIMIT 1",
                UUID.class, payment.getId(), "ESCROW_FORCE_RELEASED");
        assertThat(actorId).isEqualTo(adminId);
        assertThat(actorId).isNotEqualTo(bid.getId());
    }

    // ── retry-payout ─────────────────────────────────────────────────────────────────────────

    @Test
    void retryPayout_onlyWhenLastPayoutIsDead() throws Exception {
        payment.setStatus(PaymentStatus.RELEASED);
        when(operations.findLatest(payment.getId(), PawapayOperationKind.PAYOUT))
                .thenReturn(Optional.of(payout(PawapayOperationStatus.ACCEPTED)));
        mockMvc.perform(post("/admin/payments/{id}/mobile-money/retry-payout", payment.getId()).with(authentication(releaseAdmin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("mobile-money-retry-not-allowed"));
        verify(payoutInitiator, never()).release(any(), any(), any(), any(), any());

        when(operations.findLatest(payment.getId(), PawapayOperationKind.PAYOUT))
                .thenReturn(Optional.of(payout(PawapayOperationStatus.FAILED)));
        when(payoutInitiator.release(eq(payment), eq(bid.getId()), eq(travelerId), eq(new BigDecimal("15000")), eq("admin-retry")))
                .thenReturn(payout(PawapayOperationStatus.ACCEPTED));
        mockMvc.perform(post("/admin/payments/{id}/mobile-money/retry-payout", payment.getId()).with(authentication(releaseAdmin())))
                .andExpect(status().isOk());
        verify(entityManager).refresh(payment);
    }

    @Test
    void retryPayout_rejectedWhenLastPayoutIsCompleted() throws Exception {
        // "Terminée", distinct de "vivante" : DEAD ne contient que FAILED/SUBMIT_REJECTED — un
        // payout déjà COMPLETED doit être refusé exactement comme un payout encore ACCEPTED.
        payment.setStatus(PaymentStatus.RELEASED);
        when(operations.findLatest(payment.getId(), PawapayOperationKind.PAYOUT))
                .thenReturn(Optional.of(payout(PawapayOperationStatus.COMPLETED)));

        mockMvc.perform(post("/admin/payments/{id}/mobile-money/retry-payout", payment.getId()).with(authentication(releaseAdmin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("mobile-money-retry-not-allowed"));
        verify(payoutInitiator, never()).release(any(), any(), any(), any(), any());
    }

    /**
     * Ronde 1, point 2 (ARGENT) : le montant réémis est celui de la tentative MORTE, jamais un
     * recalcul {@code amount − commission}. Un bon de parrainage consommé à la première tentative
     * (chemin de livraison, {@code DeliveryEventListener#travelerVoucherTopUp}) majore le net
     * réellement dû (15900 ici) au-delà du recalcul brut (15000) — et ce bon ne resservira
     * jamais. Un montant identique entre les deux (comme dans les fixtures ci-dessus) aurait
     * masqué une régression : ce test les distingue délibérément.
     */
    @Test
    void retryPayout_reusesDeadOperationAmount_notRecomputedNet() throws Exception {
        payment.setStatus(PaymentStatus.RELEASED);
        BigDecimal recalculatedNet = new BigDecimal("15000"); // amount(16800) - commission(1800)
        BigDecimal deadOperationAmount = new BigDecimal("15900"); // net + majoration bon, déjà consommé
        when(operations.findLatest(payment.getId(), PawapayOperationKind.PAYOUT))
                .thenReturn(Optional.of(payout(PawapayOperationStatus.FAILED, deadOperationAmount)));
        when(payoutInitiator.release(eq(payment), eq(bid.getId()), eq(travelerId), eq(deadOperationAmount), eq("admin-retry")))
                .thenReturn(payout(PawapayOperationStatus.ACCEPTED, deadOperationAmount));

        mockMvc.perform(post("/admin/payments/{id}/mobile-money/retry-payout", payment.getId()).with(authentication(releaseAdmin())))
                .andExpect(status().isOk());

        verify(payoutInitiator).release(any(), any(), any(), eq(deadOperationAmount), eq("admin-retry"));
        verify(payoutInitiator, never()).release(any(), any(), any(), eq(recalculatedNet), any());
    }

    @Test
    void retryPayout_stripePayment_isRejected() throws Exception {
        payment.setRail(PaymentRail.STRIPE);
        mockMvc.perform(post("/admin/payments/{id}/mobile-money/retry-payout", payment.getId()).with(authentication(releaseAdmin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("mobile-money-retry-not-allowed"));
        verify(operations, never()).findLatest(any(), any());
        verify(payoutInitiator, never()).release(any(), any(), any(), any(), any());
    }

    // ── retry-refund ─────────────────────────────────────────────────────────────────────────

    @Test
    void retryRefund_resubmitsTheRefund_whenLastOneIsDead() throws Exception {
        payment.setStatus(PaymentStatus.REFUNDED);
        PawapayOperationEntity deposit = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.DEPOSIT, payment.getId(), null,
                new BigDecimal("16800"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        deposit.setStatus(PawapayOperationStatus.COMPLETED);
        PawapayOperationEntity dead = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.REFUND, payment.getId(), deposit.getId(),
                new BigDecimal("16800"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        dead.setStatus(PawapayOperationStatus.FAILED);
        PawapayOperationEntity fresh = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.REFUND, payment.getId(), deposit.getId(),
                new BigDecimal("16800"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        fresh.setStatus(PawapayOperationStatus.ACCEPTED);
        when(operations.findLatest(payment.getId(), PawapayOperationKind.REFUND)).thenReturn(Optional.of(dead));
        when(operations.findLatest(payment.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.of(deposit));
        when(submission.submitRefund(payment.getId(), deposit, new BigDecimal("16800"))).thenReturn(fresh);

        mockMvc.perform(post("/admin/payments/{id}/mobile-money/retry-refund", payment.getId()).with(authentication(releaseAdmin())))
                .andExpect(status().isOk());

        // Ronde 1, point 9 : attachRefundId (UPDATE ciblé), jamais un setter — payment.getPawapayRefundId()
        // reste donc null ici (mock, voir Javadoc de classe) ; la preuve porte sur l'appel lui-même.
        verify(paymentRepository).attachRefundId(payment.getId(), fresh.getId());
        verify(entityManager).refresh(payment);
        assertThat(payment.getPawapayRefundId()).isNull();
    }

    /**
     * Revue finale, point 3(b) (Important) : un paiement mobile money {@code PENDING} remboursé
     * devient {@code CANCELLED} — jamais {@code REFUNDED} comme sur le rail Stripe (voir
     * {@code RefundProcessor#refundMobileMoney}, cas {@code PENDING}). Un deposit arrivé tard sur
     * un tel paiement ({@code MobileMoneyBidPaymentService#confirmEscrow} →
     * {@code refundAfterCancel}) peut y soumettre un refund qui échoue à son tour : sans cet
     * élargissement, {@code retry-refund} exigeait {@code REFUNDED} et une reprise humaine était
     * structurellement impossible sur ce paiement — alors même que l'alerte
     * {@code PAWAPAY_REFUND_*} la demande. LE TEST DEMANDÉ PAR LA REVUE FINALE, point 3(b).
     */
    @Test
    void retryRefund_resubmitsTheRefund_whenPaymentIsCancelledAndLastOneIsDead() throws Exception {
        payment.setStatus(PaymentStatus.CANCELLED);
        PawapayOperationEntity deposit = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.DEPOSIT, payment.getId(), null,
                new BigDecimal("16800"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        deposit.setStatus(PawapayOperationStatus.COMPLETED);
        PawapayOperationEntity dead = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.REFUND, payment.getId(), deposit.getId(),
                new BigDecimal("16800"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        dead.setStatus(PawapayOperationStatus.FAILED);
        PawapayOperationEntity fresh = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.REFUND, payment.getId(), deposit.getId(),
                new BigDecimal("16800"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        fresh.setStatus(PawapayOperationStatus.ACCEPTED);
        when(operations.findLatest(payment.getId(), PawapayOperationKind.REFUND)).thenReturn(Optional.of(dead));
        when(operations.findLatest(payment.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.of(deposit));
        when(submission.submitRefund(payment.getId(), deposit, new BigDecimal("16800"))).thenReturn(fresh);

        mockMvc.perform(post("/admin/payments/{id}/mobile-money/retry-refund", payment.getId()).with(authentication(releaseAdmin())))
                .andExpect(status().isOk());

        verify(paymentRepository).attachRefundId(payment.getId(), fresh.getId());
        verify(entityManager).refresh(payment);
    }

    /** Le garde-fou reste fermé aux autres statuts — élargi à CANCELLED, pas à n'importe quoi. */
    @Test
    void retryRefund_rejectedWhenPaymentIsNeitherRefundedNorCancelled() throws Exception {
        payment.setStatus(PaymentStatus.ESCROW);

        mockMvc.perform(post("/admin/payments/{id}/mobile-money/retry-refund", payment.getId()).with(authentication(releaseAdmin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("mobile-money-retry-not-allowed"));
        verify(operations, never()).findLatest(any(), any());
    }

    @Test
    void retryRefund_rejectedWhenLastRefundIsLive() throws Exception {
        // Symétrique de retryPayout_onlyWhenLastPayoutIsDead : une relance sur un refund encore
        // vivant ne doit JAMAIS resoumettre — sans quoi deux remboursements pourraient partir.
        payment.setStatus(PaymentStatus.REFUNDED);
        PawapayOperationEntity live = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.REFUND, payment.getId(), null,
                new BigDecimal("16800"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        live.setStatus(PawapayOperationStatus.ACCEPTED);
        when(operations.findLatest(payment.getId(), PawapayOperationKind.REFUND)).thenReturn(Optional.of(live));

        mockMvc.perform(post("/admin/payments/{id}/mobile-money/retry-refund", payment.getId()).with(authentication(releaseAdmin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("mobile-money-retry-not-allowed"));
        verify(submission, never()).submitRefund(any(), any(), any());
    }

    /**
     * Ronde 1, point 8 : le montant resoumis est celui du DEPOSIT d'origine, jamais
     * {@code payment.getAmount()} — les deux valent 16800 dans les autres tests de ce fichier,
     * ce qui aurait masqué une régression. Ici ils diffèrent délibérément.
     */
    @Test
    void retryRefund_usesDepositAmount_notPaymentAmount() throws Exception {
        payment.setStatus(PaymentStatus.REFUNDED);
        BigDecimal depositAmount = new BigDecimal("16500"); // diffère de payment.getAmount() = 16800
        PawapayOperationEntity deposit = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.DEPOSIT, payment.getId(), null,
                depositAmount, "XOF", "ORANGE_SEN", "SN", "221771234567");
        deposit.setStatus(PawapayOperationStatus.COMPLETED);
        PawapayOperationEntity dead = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.REFUND, payment.getId(), deposit.getId(),
                depositAmount, "XOF", "ORANGE_SEN", "SN", "221771234567");
        dead.setStatus(PawapayOperationStatus.FAILED);
        PawapayOperationEntity fresh = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.REFUND, payment.getId(), deposit.getId(),
                depositAmount, "XOF", "ORANGE_SEN", "SN", "221771234567");
        fresh.setStatus(PawapayOperationStatus.ACCEPTED);
        when(operations.findLatest(payment.getId(), PawapayOperationKind.REFUND)).thenReturn(Optional.of(dead));
        when(operations.findLatest(payment.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.of(deposit));
        when(submission.submitRefund(payment.getId(), deposit, depositAmount)).thenReturn(fresh);

        mockMvc.perform(post("/admin/payments/{id}/mobile-money/retry-refund", payment.getId()).with(authentication(releaseAdmin())))
                .andExpect(status().isOk());

        verify(submission).submitRefund(payment.getId(), deposit, depositAmount);
        verify(submission, never()).submitRefund(payment.getId(), deposit, payment.getAmount());
    }

    @Test
    void retryRefund_stripePayment_isRejected() throws Exception {
        payment.setRail(PaymentRail.STRIPE);
        mockMvc.perform(post("/admin/payments/{id}/mobile-money/retry-refund", payment.getId()).with(authentication(releaseAdmin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("mobile-money-retry-not-allowed"));
        verify(operations, never()).findLatest(any(), any());
        verify(submission, never()).submitRefund(any(), any(), any());
    }

    // ── refund (rail mobile money, Ronde 1 point 4) ─────────────────────────────────────────

    @Test
    void refund_pawapay_delegatesToRefundProcessor_withoutAmbientClaim() throws Exception {
        when(refundProcessor.processRefund(eq(payment.getId()), eq("ESCROW_FORCE_REFUNDED"), any(), anyMap()))
                .thenReturn(true);

        mockMvc.perform(post("/admin/payments/{id}/refund", payment.getId()).with(authentication(refundAdmin())))
                .andExpect(status().isOk());

        verify(refundProcessor).processRefund(eq(payment.getId()), eq("ESCROW_FORCE_REFUNDED"), any(), anyMap());
        // Aucun claim ambiant pour ce rail : processRefund (REQUIRES_NEW) fait le sien. Le
        // brancher après un premier markRefundedIfEscrow ici reproduirait l'auto-interblocage
        // démontré à la tâche 17 (transaction ambiante bloquée sur son propre verrou de ligne).
        verify(paymentRepository, never()).markRefundedIfEscrow(any());
        verify(entityManager).refresh(payment);
    }

    @Test
    void refund_pawapay_processRefundReturnsFalse_is422() throws Exception {
        // Race perdue entre notre lecture du statut et le claim interne de processRefund.
        when(refundProcessor.processRefund(any(), any(), any(), anyMap())).thenReturn(false);

        mockMvc.perform(post("/admin/payments/{id}/refund", payment.getId()).with(authentication(refundAdmin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("payment-not-in-escrow"));
        verify(entityManager, never()).refresh(any());
    }

    @Test
    void refund_pawapay_notInEscrow_is422_withoutCallingProcessRefund() throws Exception {
        payment.setStatus(PaymentStatus.PENDING);
        mockMvc.perform(post("/admin/payments/{id}/refund", payment.getId()).with(authentication(refundAdmin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("payment-not-in-escrow"));
        verify(refundProcessor, never()).processRefund(any(), any(), any(), anyMap());
    }

    // ── list() (Ronde 1, point 6) ───────────────────────────────────────────────────────────

    /**
     * Avant cette ronde, {@code method != STRIPE} court-circuitait sur une page vide (raccourci
     * devenu faux depuis la tâche 12 : les paiements mobile money créent bien une ligne
     * {@code payments}) et {@code AdminPaymentListItemResponse} rendait "STRIPE" en dur — la
     * liste et le détail (déjà corrigé) devenaient incohérents pour le même paiement.
     */
    @Test
    void list_method_pawapay_filtersAndReportsCorrectly() throws Exception {
        when(paymentRepository.findAdminFiltered(any(), any(), any(), eq("PAWAPAY"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(payment), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/admin/payments").param("method", "PAWAPAY").with(authentication(viewAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].method").value("PAWAPAY"));

        verify(paymentRepository).findAdminFiltered(any(), any(), any(), eq("PAWAPAY"), any(Pageable.class));
    }

    // ── Autorité : PAYMENT_RELEASE obligatoire sur les trois endroits qui déplacent de l'argent ──

    private static UsernamePasswordAuthenticationToken adminWithoutPaymentRelease() {
        AdminPrincipal principal = new AdminPrincipal(UUID.randomUUID(), "sans-release@yadony.test", AdminRole.ADMIN, false, "uid-no-release");
        return new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @Test
    void forceRelease_withoutPaymentRelease_isForbidden() throws Exception {
        mockMvc.perform(post("/admin/payments/{id}/force-release", payment.getId()).with(authentication(adminWithoutPaymentRelease())))
                .andExpect(status().isForbidden());
        verify(paymentRepository, never()).markReleasedIfEscrow(any(), any());
        verify(payoutInitiator, never()).release(any(), any(), any(), any(), any());
    }

    @Test
    void retryPayout_withoutPaymentRelease_isForbidden() throws Exception {
        mockMvc.perform(post("/admin/payments/{id}/mobile-money/retry-payout", payment.getId()).with(authentication(adminWithoutPaymentRelease())))
                .andExpect(status().isForbidden());
        verify(operations, never()).findLatest(any(), any());
        verify(payoutInitiator, never()).release(any(), any(), any(), any(), any());
    }

    @Test
    void retryRefund_withoutPaymentRelease_isForbidden() throws Exception {
        mockMvc.perform(post("/admin/payments/{id}/mobile-money/retry-refund", payment.getId()).with(authentication(adminWithoutPaymentRelease())))
                .andExpect(status().isForbidden());
        verify(operations, never()).findLatest(any(), any());
        verify(submission, never()).submitRefund(any(), any(), any());
    }
}
