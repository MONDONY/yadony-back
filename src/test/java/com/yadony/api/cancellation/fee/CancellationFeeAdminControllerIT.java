package com.yadony.api.cancellation.fee;

import com.yadony.api.admin.account.AdminPermission;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.cancellation.dto.AdminCancellationFeeResponse;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Gestes admin sur une retenue d'annulation : renoncer (DUE seulement), relancer un versement en attente. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CancellationFeeAdminControllerIT {

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID BID_ID = UUID.randomUUID();
    private static final UUID FEE_ID = UUID.randomUUID();
    private static final String REASON = "Partiel refusé par l'opérateur, remboursement intégral";
    private static final String BODY = "{\"reason\":\"" + REASON + "\"}";

    @Autowired MockMvc mockMvc;
    @MockitoBean CancellationFeeAdminService service;

    /** Compte admin d'un rôle donné, avec ses permissions et ROLE_ADMIN (comme AdminAuthService le pose toujours). */
    private static UsernamePasswordAuthenticationToken admin(AdminRole role) {
        var principal = new AdminPrincipal(ADMIN_ID, "admin@yadony.com", role, false, "uid");
        var all = new ArrayList<SimpleGrantedAuthority>();
        role.permissions().forEach(p -> all.add(new SimpleGrantedAuthority(p.name())));
        all.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new UsernamePasswordAuthenticationToken(principal, null, all);
    }

    /** Rôle ADMIN : PAYMENT_REFUND et PAYMENT_RELEASE compris. */
    private static UsernamePasswordAuthenticationToken admin() {
        return admin(AdminRole.ADMIN);
    }

    /** Utilisateur de l'app (expéditeur et voyageur), jamais administrateur. */
    private static UsernamePasswordAuthenticationToken appUser() {
        return new UsernamePasswordAuthenticationToken("uid-user", null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER"), new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private static AdminCancellationFeeResponse response(String status, String pendingReason) {
        return new AdminCancellationFeeResponse(FEE_ID, BID_ID, status, "SENDER_CANCEL", new BigDecimal("0.3000"),
                new BigDecimal("20000"), new BigDecimal("1200"), new BigDecimal("1477"), new BigDecimal("17323"),
                "XOF", null, pendingReason, LocalDateTime.parse("2026-11-20T09:30:00"),
                LocalDateTime.parse("2026-11-21T10:00:00"), "DUE".equals(status), "PAYOUT_PENDING".equals(status));
    }

    // ── Lecture de la retenue d'un colis ─────────────────────────────────────────────────────────────────

    @Test
    void read_due_200_waivableNotRetryable_moneyAsNumbers() throws Exception {
        when(service.describeByBid(BID_ID)).thenReturn(response("DUE", null));

        mockMvc.perform(get("/admin/cancellation-fees/bids/{bidId}", BID_ID).with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(FEE_ID.toString()))
                .andExpect(jsonPath("$.bidId").value(BID_ID.toString()))
                .andExpect(jsonPath("$.status").value("DUE"))
                .andExpect(jsonPath("$.reason").value("SENDER_CANCEL"))
                .andExpect(jsonPath("$.rate").value(0.3))
                .andExpect(jsonPath("$.grossFee").value(20000))
                .andExpect(jsonPath("$.refundFees").value(1200))
                .andExpect(jsonPath("$.commission").value(1477))
                .andExpect(jsonPath("$.travelerNet").value(17323))
                .andExpect(jsonPath("$.currency").value("XOF"))
                .andExpect(jsonPath("$.payoutPendingReason").doesNotExist())
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andExpect(jsonPath("$.waivable").value(true))
                .andExpect(jsonPath("$.payoutRetryable").value(false));
    }

    @Test
    void read_payoutPending_200_retryableNotWaivable() throws Exception {
        when(service.describeByBid(BID_ID)).thenReturn(response("PAYOUT_PENDING", "TOO_MANY_FAILED_PAYOUTS"));

        mockMvc.perform(get("/admin/cancellation-fees/bids/{bidId}", BID_ID).with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payoutPendingReason").value("TOO_MANY_FAILED_PAYOUTS"))
                .andExpect(jsonPath("$.waivable").value(false))
                .andExpect(jsonPath("$.payoutRetryable").value(true));
    }

    @Test
    void read_supportWithPaymentView_200() throws Exception {
        assertThat(AdminRole.SUPPORT.permissions()).contains(AdminPermission.PAYMENT_VIEW);
        when(service.describeByBid(BID_ID)).thenReturn(response("RETAINED", null));

        mockMvc.perform(get("/admin/cancellation-fees/bids/{bidId}", BID_ID)
                        .with(authentication(admin(AdminRole.SUPPORT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waivable").value(false))
                .andExpect(jsonPath("$.payoutRetryable").value(false));
    }

    @Test
    void read_adminWithoutPaymentView_403() throws Exception {
        var principal = new AdminPrincipal(ADMIN_ID, "admin@yadony.com", AdminRole.SUPPORT, false, "uid");
        var noPayment = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("USER_VIEW"), new SimpleGrantedAuthority("ROLE_ADMIN")));

        mockMvc.perform(get("/admin/cancellation-fees/bids/{bidId}", BID_ID).with(authentication(noPayment)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void read_nonAdmin_403() throws Exception {
        mockMvc.perform(get("/admin/cancellation-fees/bids/{bidId}", BID_ID).with(authentication(appUser())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void read_unknownBid_404ProblemDetail() throws Exception {
        when(service.describeByBid(BID_ID)).thenThrow(new YadonyBusinessException(HttpStatus.NOT_FOUND,
                "cancellation-fee-not-found", "Not Found", "Aucune retenue pour ce colis"));

        mockMvc.perform(get("/admin/cancellation-fees/bids/{bidId}", BID_ID).with(authentication(admin())))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("cancellation-fee-not-found"));
    }

    // ── Renoncer à la retenue ────────────────────────────────────────────────────────────────────────────

    @Test
    void waive_admin_200() throws Exception {
        when(service.waive(BID_ID, ADMIN_ID, REASON)).thenReturn(response("NOT_APPLICABLE", null));

        mockMvc.perform(post("/admin/cancellation-fees/bids/{bidId}/waive", BID_ID).contentType("application/json")
                        .content(BODY).with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$.bidId").value(BID_ID.toString()));

        verify(service).waive(BID_ID, ADMIN_ID, REASON);
    }

    @Test
    void waive_nonAdmin_403() throws Exception {
        mockMvc.perform(post("/admin/cancellation-fees/bids/{bidId}/waive", BID_ID).contentType("application/json")
                        .content(BODY).with(authentication(appUser())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void waive_supportAdmin_withoutPaymentRefund_403() throws Exception {
        // Revue tâche 7, tour 2 : ROLE_ADMIN est posé sur tout compte admin, SUPPORT compris ; la permission tranche.
        assertThat(AdminRole.SUPPORT.permissions()).doesNotContain(AdminPermission.PAYMENT_REFUND);

        mockMvc.perform(post("/admin/cancellation-fees/bids/{bidId}/waive", BID_ID).contentType("application/json")
                        .content(BODY).with(authentication(admin(AdminRole.SUPPORT))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void waive_superAdmin_200() throws Exception {
        when(service.waive(BID_ID, ADMIN_ID, REASON)).thenReturn(response("NOT_APPLICABLE", null));

        mockMvc.perform(post("/admin/cancellation-fees/bids/{bidId}/waive", BID_ID).contentType("application/json")
                        .content(BODY).with(authentication(admin(AdminRole.SUPER_ADMIN))))
                .andExpect(status().isOk());
    }

    @Test
    void waive_rowNotDue_409ProblemDetail() throws Exception {
        when(service.waive(any(), any(), any())).thenThrow(new YadonyBusinessException(HttpStatus.CONFLICT,
                "cancellation-fee-not-due", "Cancellation Fee Not Due", "Retenue déjà réglée"));

        mockMvc.perform(post("/admin/cancellation-fees/bids/{bidId}/waive", BID_ID).contentType("application/json")
                        .content(BODY).with(authentication(admin())))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("cancellation-fee-not-due"));
    }

    @Test
    void waive_unknownBid_404ProblemDetail() throws Exception {
        when(service.waive(any(), any(), any())).thenThrow(new YadonyBusinessException(HttpStatus.NOT_FOUND,
                "cancellation-fee-not-found", "Not Found", "Aucune retenue pour ce colis"));

        mockMvc.perform(post("/admin/cancellation-fees/bids/{bidId}/waive", BID_ID).contentType("application/json")
                        .content(BODY).with(authentication(admin())))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("cancellation-fee-not-found"));
    }

    @Test
    void waive_withoutReason_422() throws Exception {
        mockMvc.perform(post("/admin/cancellation-fees/bids/{bidId}/waive", BID_ID).contentType("application/json")
                        .content("{\"reason\":\"court\"}").with(authentication(admin())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verifyNoInteractions(service);
    }

    // ── Relancer le versement ────────────────────────────────────────────────────────────────────────────

    @Test
    void retryPayout_admin_200_withTheStateAfterTheAttempt() throws Exception {
        when(service.describe(FEE_ID)).thenReturn(response("PAID_OUT", "TOO_MANY_FAILED_PAYOUTS"));

        mockMvc.perform(post("/admin/cancellation-fees/{feeId}/retry-payout", FEE_ID).contentType("application/json")
                        .content(BODY).with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID_OUT"))
                .andExpect(jsonPath("$.id").value(FEE_ID.toString()));

        verify(service).retryPayout(FEE_ID, ADMIN_ID, REASON);
    }

    @Test
    void retryPayout_nonAdmin_403() throws Exception {
        mockMvc.perform(post("/admin/cancellation-fees/{feeId}/retry-payout", FEE_ID).contentType("application/json")
                        .content(BODY).with(authentication(appUser())))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void retryPayout_supportAdmin_withoutPaymentRelease_403() throws Exception {
        assertThat(AdminRole.SUPPORT.permissions()).doesNotContain(AdminPermission.PAYMENT_RELEASE);

        mockMvc.perform(post("/admin/cancellation-fees/{feeId}/retry-payout", FEE_ID).contentType("application/json")
                        .content(BODY).with(authentication(admin(AdminRole.SUPPORT))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void retryPayout_superAdmin_200() throws Exception {
        when(service.describe(FEE_ID)).thenReturn(response("PAYOUT_PENDING", "TOO_MANY_FAILED_PAYOUTS"));

        mockMvc.perform(post("/admin/cancellation-fees/{feeId}/retry-payout", FEE_ID).contentType("application/json")
                        .content(BODY).with(authentication(admin(AdminRole.SUPER_ADMIN))))
                .andExpect(status().isOk());
    }

    @Test
    void retryPayout_rowNotPending_409() throws Exception {
        org.mockito.Mockito.doThrow(new YadonyBusinessException(HttpStatus.CONFLICT, "cancellation-fee-not-pending",
                "Cancellation Fee Payout Not Pending", "Versement pas en attente"))
                .when(service).retryPayout(any(), any(), any());

        mockMvc.perform(post("/admin/cancellation-fees/{feeId}/retry-payout", FEE_ID).contentType("application/json")
                        .content(BODY).with(authentication(admin())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("cancellation-fee-not-pending"));
    }

    @Test
    void retryPayout_unknownFee_404() throws Exception {
        org.mockito.Mockito.doThrow(new YadonyBusinessException(HttpStatus.NOT_FOUND, "cancellation-fee-not-found",
                "Not Found", "Retenue introuvable"))
                .when(service).retryPayout(any(), any(), any());

        mockMvc.perform(post("/admin/cancellation-fees/{feeId}/retry-payout", FEE_ID).contentType("application/json")
                        .content(BODY).with(authentication(admin())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("cancellation-fee-not-found"));
    }
}
