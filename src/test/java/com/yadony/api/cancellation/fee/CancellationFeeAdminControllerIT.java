package com.yadony.api.cancellation.fee;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
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

    private static UsernamePasswordAuthenticationToken admin() {
        var principal = new AdminPrincipal(ADMIN_ID, "admin@yadony.com", AdminRole.ADMIN, false, "uid");
        var all = new ArrayList<SimpleGrantedAuthority>();
        AdminRole.ADMIN.permissions().forEach(p -> all.add(new SimpleGrantedAuthority(p.name())));
        all.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new UsernamePasswordAuthenticationToken(principal, null, all);
    }

    /** Utilisateur de l'app (expéditeur et voyageur), jamais administrateur. */
    private static UsernamePasswordAuthenticationToken appUser() {
        return new UsernamePasswordAuthenticationToken("uid-user", null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER"), new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private static AdminCancellationFeeResponse response(String status, String pendingReason) {
        return new AdminCancellationFeeResponse(FEE_ID, BID_ID, status, new BigDecimal("17323"), "XOF", null,
                pendingReason);
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
