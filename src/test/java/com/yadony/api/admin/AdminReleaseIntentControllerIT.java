package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.StripeReleaseExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contrat HTTP de {@code POST /admin/payments/{id}/release-intent/cancel} (super-admin, motif obligatoire). */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminReleaseIntentControllerIT {

    private static final String BODY = """
            {"reason": "Compte Connect du voyageur rejeté par Stripe"}
            """;

    @Autowired MockMvc mockMvc;
    @MockitoBean StripeReleaseExecutor releaseExecutor;
    @MockitoBean PaymentRepository paymentRepository;

    private final UUID paymentId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();

    private UsernamePasswordAuthenticationToken admin(AdminRole role, String... authorities) {
        AdminPrincipal principal = new AdminPrincipal(adminId, "admin@yadony.test", role, false, "uid-admin-cancel");
        List<SimpleGrantedAuthority> granted = new ArrayList<>(List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        for (String a : authorities) granted.add(new SimpleGrantedAuthority(a));
        return new UsernamePasswordAuthenticationToken(principal, null, granted);
    }

    private org.springframework.test.web.servlet.ResultActions cancel(String body, UsernamePasswordAuthenticationToken auth)
            throws Exception {
        return mockMvc.perform(post("/admin/payments/{id}/release-intent/cancel", paymentId)
                .contentType(MediaType.APPLICATION_JSON).content(body).with(authentication(auth)));
    }

    @Test
    void sansAdminManage_403_rienNeChange() throws Exception {
        cancel(BODY, admin(AdminRole.ADMIN, "PAYMENT_RELEASE", "PAYMENT_REFUND")).andExpect(status().isForbidden());
        verify(releaseExecutor, never()).cancelReservation(any(), any(), any());
    }

    @Test
    void motifAbsentOuTropCourt_422() throws Exception {
        cancel("{}", admin(AdminRole.SUPER_ADMIN, "ADMIN_MANAGE")).andExpect(status().isUnprocessableEntity());
        cancel("{\"reason\": \"court\"}", admin(AdminRole.SUPER_ADMIN, "ADMIN_MANAGE")).andExpect(status().isUnprocessableEntity());
        verify(releaseExecutor, never()).cancelReservation(any(), any(), any());
    }

    @Test
    void aucunTransfer_200Cancelled_paiementEscrow() throws Exception {
        when(releaseExecutor.cancelReservation(eq(paymentId), eq(adminId), eq("Compte Connect du voyageur rejeté par Stripe")))
                .thenReturn(new StripeReleaseExecutor.Cancellation(StripeReleaseExecutor.CancelResult.CANCELLED, null, null));
        when(paymentRepository.findStatusById(paymentId)).thenReturn(Optional.of(PaymentStatus.ESCROW));

        cancel(BODY, admin(AdminRole.SUPER_ADMIN, "ADMIN_MANAGE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
                .andExpect(jsonPath("$.result").value("CANCELLED"))
                .andExpect(jsonPath("$.paymentStatus").value("ESCROW"));
    }

    @Test
    void transferTrouve_200Realigned() throws Exception {
        when(releaseExecutor.cancelReservation(any(), any(), any()))
                .thenReturn(new StripeReleaseExecutor.Cancellation(StripeReleaseExecutor.CancelResult.REALIGNED, "tr_1", null));
        when(paymentRepository.findStatusById(paymentId)).thenReturn(Optional.of(PaymentStatus.RELEASED));

        cancel(BODY, admin(AdminRole.SUPER_ADMIN, "ADMIN_MANAGE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("REALIGNED"))
                .andExpect(jsonPath("$.transferId").value("tr_1"))
                .andExpect(jsonPath("$.paymentStatus").value("RELEASED"));
    }

    @Test
    void rechercheStripeImpossible_502() throws Exception {
        when(releaseExecutor.cancelReservation(any(), any(), any()))
                .thenReturn(new StripeReleaseExecutor.Cancellation(StripeReleaseExecutor.CancelResult.LOOKUP_FAILED, null, "x"));
        cancel(BODY, admin(AdminRole.SUPER_ADMIN, "ADMIN_MANAGE"))
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("stripe-unavailable"));
    }

    @Test
    void autresIssues_codesRfc7807() throws Exception {
        when(releaseExecutor.cancelReservation(any(), any(), any()))
                .thenReturn(new StripeReleaseExecutor.Cancellation(StripeReleaseExecutor.CancelResult.NOT_PENDING, null, null),
                        new StripeReleaseExecutor.Cancellation(StripeReleaseExecutor.CancelResult.IN_PROGRESS, null, null),
                        new StripeReleaseExecutor.Cancellation(StripeReleaseExecutor.CancelResult.UNSUPPORTED, null, null));
        cancel(BODY, admin(AdminRole.SUPER_ADMIN, "ADMIN_MANAGE"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("no-pending-release"));
        cancel(BODY, admin(AdminRole.SUPER_ADMIN, "ADMIN_MANAGE"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("release-in-progress"));
        cancel(BODY, admin(AdminRole.SUPER_ADMIN, "ADMIN_MANAGE"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("release-intent-unsupported"));
    }
}
