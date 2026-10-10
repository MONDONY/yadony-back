package com.yadony.api.cancellation;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.cancellation.dto.AdminBidCancelRequest;
import com.yadony.api.cancellation.dto.AdminBidCancelResponse;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.disputes.AdminDisputeOpeningService;
import com.yadony.api.disputes.AdminDisputeReason;
import com.yadony.api.disputes.DisputeParty;
import com.yadony.api.disputes.dto.AdminOpenDisputeRequest;
import com.yadony.api.disputes.dto.AdminOpenDisputeResponse;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Fiche colis admin : « Annuler le colis » et « Ouvrir un litige » vus de bout en bout. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminBidActionsControllerIT {

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID BID = UUID.randomUUID();
    private static final String CANCEL_BODY = "{\"reason\":\"SENDER_REQUEST\",\"note\":\"Demande écrite\"}";
    private static final String DISPUTE_BODY = "{\"openedOnBehalfOf\":\"SENDER\",\"reason\":\"PARCEL_LOST\","
            + "\"description\":\"Colis introuvable depuis l'arrivée\"}";

    @Autowired MockMvc mockMvc;
    @MockitoBean AdminBidCancellationService cancellationService;
    @MockitoBean AdminDisputeOpeningService disputeOpeningService;

    static UsernamePasswordAuthenticationToken auth(AdminRole role) {
        var principal = new AdminPrincipal(ADMIN_ID, "admin@yadony.com", role, false, "uid");
        var all = new ArrayList<SimpleGrantedAuthority>();
        role.permissions().forEach(p -> all.add(new SimpleGrantedAuthority(p.name())));
        all.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new UsernamePasswordAuthenticationToken(principal, null, all);
    }

    @Test
    void annuler_superAdmin_200() throws Exception {
        when(cancellationService.cancel(eq(BID), eq(ADMIN_ID), any(AdminBidCancelRequest.class)))
                .thenReturn(new AdminBidCancelResponse(BID, "CANCELLED", "ACCEPTED", false, true, "ESCROW",
                        new BigDecimal("42.00"), "EUR", false));

        mockMvc.perform(post("/admin/bids/{id}/cancel", BID).contentType("application/json").content(CANCEL_BODY)
                        .with(authentication(auth(AdminRole.SUPER_ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.refundRequested").value(true))
                .andExpect(jsonPath("$.refundAmount").value(42.00));

        verify(cancellationService).cancel(BID, ADMIN_ID,
                new AdminBidCancelRequest(AdminBidCancelReason.SENDER_REQUEST, "Demande écrite"));
    }

    @Test
    void annuler_adminSansAdminManage_403() throws Exception {
        mockMvc.perform(post("/admin/bids/{id}/cancel", BID).contentType("application/json").content(CANCEL_BODY)
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/bids/{id}/cancel", BID).contentType("application/json").content(CANCEL_BODY)
                        .with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(cancellationService);
    }

    @Test
    void annuler_sansMotif_422ProblemDetail() throws Exception {
        mockMvc.perform(post("/admin/bids/{id}/cancel", BID).contentType("application/json").content("{\"note\":\"x\"}")
                        .with(authentication(auth(AdminRole.SUPER_ADMIN))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        verifyNoInteractions(cancellationService);
    }

    @Test
    void annuler_colisLivre_409ProblemDetailAvecCode() throws Exception {
        when(cancellationService.cancel(any(), any(), any())).thenThrow(new YadonyBusinessException(
                HttpStatus.CONFLICT, "bid-delivered", "Bid Not Cancellable", "Colis déjà livré"));

        mockMvc.perform(post("/admin/bids/{id}/cancel", BID).contentType("application/json").content(CANCEL_BODY)
                        .with(authentication(auth(AdminRole.SUPER_ADMIN))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("bid-delivered"));
    }

    @Test
    void ouvrirLitige_superAdmin_201() throws Exception {
        UUID disputeId = UUID.randomUUID();
        when(disputeOpeningService.open(eq(BID), eq(ADMIN_ID), any(AdminOpenDisputeRequest.class)))
                .thenReturn(new AdminOpenDisputeResponse(disputeId, BID, "ADMIN_PARCEL_LOST", "OPEN", "SENDER",
                        true, "ESCROW"));

        mockMvc.perform(post("/admin/bids/{id}/disputes", BID).contentType("application/json").content(DISPUTE_BODY)
                        .with(authentication(auth(AdminRole.SUPER_ADMIN))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.disputeId").value(disputeId.toString()))
                .andExpect(jsonPath("$.payoutFrozen").value(true));

        verify(disputeOpeningService).open(BID, ADMIN_ID, new AdminOpenDisputeRequest(DisputeParty.SENDER,
                AdminDisputeReason.PARCEL_LOST, "Colis introuvable depuis l'arrivée"));
    }

    @Test
    void ouvrirLitige_adminSansAdminManage_403() throws Exception {
        mockMvc.perform(post("/admin/bids/{id}/disputes", BID).contentType("application/json").content(DISPUTE_BODY)
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(disputeOpeningService);
    }

    @Test
    void ouvrirLitige_descriptionTropCourte_422() throws Exception {
        mockMvc.perform(post("/admin/bids/{id}/disputes", BID).contentType("application/json")
                        .content("{\"openedOnBehalfOf\":\"SENDER\",\"reason\":\"OTHER\",\"description\":\"court\"}")
                        .with(authentication(auth(AdminRole.SUPER_ADMIN))))
                .andExpect(status().isUnprocessableEntity());
        verifyNoInteractions(disputeOpeningService);
    }
}
