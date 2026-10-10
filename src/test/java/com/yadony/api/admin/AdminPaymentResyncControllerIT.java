package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.PaymentStripeResyncService;
import com.yadony.api.payments.PaymentStripeResyncService.Action;
import com.yadony.api.payments.PaymentStripeResyncService.Snapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /admin/payments/{id}/resync-stripe} : garde super-admin, contrat de réponse,
 * erreurs RFC 7807, clôture automatique des alertes résolues. La logique de resynchronisation
 * (Stripe simulé) est couverte par {@code PaymentStripeResyncServiceTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminPaymentResyncControllerIT {

    @Autowired MockMvc mockMvc;
    @MockitoBean PaymentStripeResyncService resyncService;
    @MockitoBean AdminAlertRepository alertRepository;

    private final UUID paymentId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();

    private static UsernamePasswordAuthenticationToken admin(UUID id, AdminRole role, String... authorities) {
        AdminPrincipal principal = new AdminPrincipal(id, "admin@yadony.test", role, false, "uid-" + id);
        List<SimpleGrantedAuthority> granted = new java.util.ArrayList<>(List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        for (String a : authorities) {
            granted.add(new SimpleGrantedAuthority(a));
        }
        return new UsernamePasswordAuthenticationToken(principal, null, granted);
    }

    private UsernamePasswordAuthenticationToken superAdmin() {
        return admin(adminId, AdminRole.SUPER_ADMIN, "ADMIN_MANAGE", "PAYMENT_RELEASE", "PAYMENT_VIEW");
    }

    private static AdminAlertEntity alert(String type, String payload) {
        AdminAlertEntity a = new AdminAlertEntity();
        ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
        a.setType(type);
        a.setPayload(payload);
        a.setSeverity("CRITICAL");
        return a;
    }

    @Test
    void adminWithoutAdminManage_is403_andNothingRuns() throws Exception {
        mockMvc.perform(post("/admin/payments/{id}/resync-stripe", paymentId)
                        .with(authentication(admin(adminId, AdminRole.ADMIN, "PAYMENT_RELEASE", "PAYMENT_VIEW"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(resyncService);
    }

    @Test
    void pendingAuthorized_isActivated_andAuthorizedNotRecordedAlertIsResolved() throws Exception {
        when(resyncService.resync(paymentId, adminId)).thenReturn(new PaymentStripeResyncService.Result(paymentId, "pi_1",
                Action.ESCROW_ACTIVATED,
                new Snapshot("PENDING", null, null, "requires_capture", 6450L),
                new Snapshot("ESCROW", null, "ch_1", "requires_capture", 6450L),
                "Paiement passé en séquestre"));
        AdminAlertEntity recon = alert("RECON_STRIPE_" + paymentId, "{\"ecart\":\"AUTORISE_NON_ENREGISTRE\"}");
        when(alertRepository.findByTypeAndResolved("RECON_STRIPE_" + paymentId, false)).thenReturn(List.of(recon));
        when(alertRepository.findByTypeAndResolved("ESCROW_CAPTURE_FAILED_" + paymentId, false)).thenReturn(List.of());

        mockMvc.perform(post("/admin/payments/{id}/resync-stripe", paymentId).with(authentication(superAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("ESCROW_ACTIVATED"))
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.before.status").value("PENDING"))
                .andExpect(jsonPath("$.after.status").value("ESCROW"))
                .andExpect(jsonPath("$.after.stripeStatus").value("requires_capture"))
                .andExpect(jsonPath("$.message").value("Paiement passé en séquestre"))
                .andExpect(jsonPath("$.resolvedAlertIds[0]").value(recon.getId().toString()))
                .andExpect(jsonPath("$.alertResolvable").value(false));

        assertThat(recon.isResolved()).isTrue();
        assertThat(recon.getResolvedAt()).isNotNull();
        verify(alertRepository).save(recon);
    }

    @Test
    void escrowCaptured_resolvesUncapturedAndCaptureFailedAlerts() throws Exception {
        when(resyncService.resync(paymentId, adminId)).thenReturn(new PaymentStripeResyncService.Result(paymentId, "pi_1",
                Action.ESCROW_CAPTURED,
                new Snapshot("ESCROW", null, null, "requires_capture", 6450L),
                new Snapshot("ESCROW", Instant.parse("2026-10-10T08:00:00Z"), "ch_1", "succeeded", 0L),
                "Séquestre capturé"));
        AdminAlertEntity recon = alert("RECON_STRIPE_" + paymentId, "{\"ecart\":\"SEQUESTRE_NON_CAPTURE\"}");
        AdminAlertEntity failed = alert("ESCROW_CAPTURE_FAILED_" + paymentId, "{}");
        when(alertRepository.findByTypeAndResolved("RECON_STRIPE_" + paymentId, false)).thenReturn(List.of(recon));
        when(alertRepository.findByTypeAndResolved("ESCROW_CAPTURE_FAILED_" + paymentId, false)).thenReturn(List.of(failed));

        mockMvc.perform(post("/admin/payments/{id}/resync-stripe", paymentId).with(authentication(superAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.after.capturedAt").value("2026-10-10T08:00:00Z"))
                .andExpect(jsonPath("$.resolvedAlertIds.length()").value(2));
        assertThat(recon.isResolved()).isTrue();
        assertThat(failed.isResolved()).isTrue();
    }

    @Test
    void alertWithAnotherMismatch_staysOpen_andIsReportedResolvable() throws Exception {
        when(resyncService.resync(paymentId, adminId)).thenReturn(new PaymentStripeResyncService.Result(paymentId, "pi_1",
                Action.ALREADY_IN_SYNC,
                new Snapshot("ESCROW", Instant.now(), "ch_1", "succeeded", 0L),
                new Snapshot("ESCROW", Instant.now(), "ch_1", "succeeded", 0L),
                "Séquestre capturé : base déjà à jour"));
        AdminAlertEntity recon = alert("RECON_STRIPE_" + paymentId, "{\"ecart\":\"SEQUESTRE_NON_CAPTURE,REMBOURSE_DIFFERENT\"}");
        when(alertRepository.findByTypeAndResolved("RECON_STRIPE_" + paymentId, false)).thenReturn(List.of(recon));
        when(alertRepository.findByTypeAndResolved("ESCROW_CAPTURE_FAILED_" + paymentId, false)).thenReturn(List.of());

        mockMvc.perform(post("/admin/payments/{id}/resync-stripe", paymentId).with(authentication(superAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("ALREADY_IN_SYNC"))
                .andExpect(jsonPath("$.changed").value(false))
                .andExpect(jsonPath("$.openAlertIds[0]").value(recon.getId().toString()))
                .andExpect(jsonPath("$.alertResolvable").value(true));
        assertThat(recon.isResolved()).isFalse();
        verify(alertRepository, never()).save(any());
    }

    @Test
    void expiredAuthorization_isProblemDetail409() throws Exception {
        when(resyncService.resync(eq(paymentId), any())).thenThrow(new YadonyBusinessException(HttpStatus.CONFLICT,
                "authorization-expired", "Authorization Expired", "L'autorisation carte a expiré"));

        mockMvc.perform(post("/admin/payments/{id}/resync-stripe", paymentId).with(authentication(superAdmin())))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value("L'autorisation carte a expiré"));
        verifyNoInteractions(alertRepository);
    }

    @Test
    void unresolvableAlertPayloads_stayOpen() {
        Snapshot captured = new Snapshot("ESCROW", Instant.now(), "ch", "succeeded", 0L);
        assertThat(AdminPaymentResyncController.isResolvedBy(alert("RECON_STRIPE_x", null), captured)).isFalse();
        assertThat(AdminPaymentResyncController.isResolvedBy(alert("RECON_STRIPE_x", "pas du json"), captured)).isFalse();
        assertThat(AdminPaymentResyncController.isResolvedBy(alert("RECON_STRIPE_x", "{\"ecart\":\"\"}"), captured)).isFalse();
        Snapshot stillAuthorized = new Snapshot("ESCROW", null, null, "requires_capture", 6450L);
        assertThat(AdminPaymentResyncController.isResolvedBy(alert("ESCROW_CAPTURE_FAILED_x", "{}"), stillAuthorized)).isFalse();
        assertThat(AdminPaymentResyncController.isResolvedBy(
                alert("RECON_STRIPE_x", "{\"ecart\":\"SEQUESTRE_NON_CAPTURE\"}"), stillAuthorized)).isFalse();
    }
}
