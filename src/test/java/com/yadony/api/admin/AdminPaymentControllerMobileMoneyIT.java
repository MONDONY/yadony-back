package com.yadony.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
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
import com.yadony.api.payments.mobilemoney.MobileMoneyPayoutInitiator;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tâche 18 — force-release et relances mobile money de {@link AdminPaymentController}.
 *
 * <p>Montage d'authentification admin repris de {@code AdminFinanceControllerIT} : un
 * {@link AdminPrincipal} authentifié directement via {@code authentication(...)}, sans passer
 * par {@code FirebaseTokenFilter}/{@code AdminAuthService}.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminPaymentControllerMobileMoneyIT {

    @Autowired MockMvc mockMvc;
    @MockitoBean PaymentRepository paymentRepository;
    @MockitoBean BidRepository bidRepository;
    @MockitoBean AnnouncementRepository announcementRepository;
    @MockitoBean UserRepository userRepository;
    @MockitoBean MobileMoneyPayoutInitiator payoutInitiator;
    @MockitoBean PawapayOperationService operations;
    @MockitoBean PawapaySubmissionService submission;

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

    private static UsernamePasswordAuthenticationToken releaseAdmin() {
        AdminPrincipal principal = new AdminPrincipal(UUID.randomUUID(), "admin@yadony.test", AdminRole.ADMIN, false, "uid-admin-mm");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("PAYMENT_RELEASE")));
    }

    private PawapayOperationEntity payout(PawapayOperationStatus status) {
        PawapayOperationEntity o = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.PAYOUT, payment.getId(), null,
                new BigDecimal("15000"), "XOF", "ORANGE_SEN", "SN", "221771234567");
        o.setStatus(status);
        return o;
    }

    @Test
    void forceRelease_pawapay_usesInitiator_notStripe() throws Exception {
        when(paymentRepository.markReleasedIfEscrow(eq(payment.getId()), any())).thenReturn(1);
        when(payoutInitiator.release(eq(payment), eq(bid.getId()), eq(travelerId), eq(new BigDecimal("15000")), eq("admin-force-release")))
                .thenReturn(payout(PawapayOperationStatus.ACCEPTED));

        mockMvc.perform(post("/admin/payments/{id}/force-release", payment.getId()).with(authentication(releaseAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.rail").value("PAWAPAY"));
        verify(payoutInitiator).release(any(), any(), any(), any(), eq("admin-force-release"));
    }

    @Test
    void forceRelease_pawapay_initiatorFailure_is422_andRollsBack() throws Exception {
        when(paymentRepository.markReleasedIfEscrow(eq(payment.getId()), any())).thenReturn(1);
        when(payoutInitiator.release(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("INSUFFICIENT_BALANCE"));

        mockMvc.perform(post("/admin/payments/{id}/force-release", payment.getId()).with(authentication(releaseAdmin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("mobile-money-payout-failed"));
    }

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

    @Test
    void retryRefund_rejectedWhenLastRefundIsLive() throws Exception {
        // Symétrique de retryPayout_onlyWhenLastPayoutIsDead, côté remboursement : une relance
        // sur un refund encore vivant ne doit JAMAIS resoumettre — sans quoi deux remboursements
        // pourraient partir pour le même paiement.
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
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pawapayRefundId").value(fresh.getId().toString()));
        assertThat(payment.getPawapayRefundId()).isEqualTo(fresh.getId());
    }

    // ── Autorité : PAYMENT_RELEASE obligatoire sur les trois endpoints qui déplacent de l'argent ──

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
