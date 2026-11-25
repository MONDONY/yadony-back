package com.yadony.api.tracking;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.tracking.dto.AdminBidStatusChangeRequest;
import com.yadony.api.tracking.dto.AdminBidStatusChangeResponse;
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

/** Fiche colis admin : « Changer le statut », réservé au super-admin. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminBidStatusControllerIT {

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID BID = UUID.randomUUID();
    private static final String BODY =
            "{\"status\":\"COMPLETED\",\"reason\":\"Message WhatsApp du destinataire du 24/11\"}";

    @Autowired MockMvc mockMvc;
    @MockitoBean AdminBidStatusService service;

    static UsernamePasswordAuthenticationToken auth(AdminRole role) {
        var principal = new AdminPrincipal(ADMIN_ID, "admin@yadony.com", role, false, "uid");
        var all = new ArrayList<SimpleGrantedAuthority>();
        role.permissions().forEach(p -> all.add(new SimpleGrantedAuthority(p.name())));
        all.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new UsernamePasswordAuthenticationToken(principal, null, all);
    }

    @Test
    void superAdmin_200() throws Exception {
        when(service.changeStatus(eq(BID), eq(ADMIN_ID), any(AdminBidStatusChangeRequest.class)))
                .thenReturn(new AdminBidStatusChangeResponse(BID, "COMPLETED", "HANDED_OVER", false));

        mockMvc.perform(post("/admin/bids/{id}/status", BID).contentType("application/json").content(BODY)
                        .with(authentication(auth(AdminRole.SUPER_ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.previousStatus").value("HANDED_OVER"))
                .andExpect(jsonPath("$.unchanged").value(false));

        verify(service).changeStatus(BID, ADMIN_ID, new AdminBidStatusChangeRequest(
                AdminForcedBidStatus.COMPLETED, "Message WhatsApp du destinataire du 24/11"));
    }

    @Test
    void adminEtSupport_403() throws Exception {
        mockMvc.perform(post("/admin/bids/{id}/status", BID).contentType("application/json").content(BODY)
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/bids/{id}/status", BID).contentType("application/json").content(BODY)
                        .with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void sansMotif_ouMotifCourt_ouStatutInconnu_422() throws Exception {
        for (String body : new String[]{
                "{\"status\":\"COMPLETED\"}",
                "{\"status\":\"COMPLETED\",\"reason\":\"court\"}",
                "{\"status\":\"CANCELLED\",\"reason\":\"Message WhatsApp du destinataire\"}"}) {
            mockMvc.perform(post("/admin/bids/{id}/status", BID).contentType("application/json").content(body)
                            .with(authentication(auth(AdminRole.SUPER_ADMIN))))
                    .andExpect(status().is4xxClientError());
        }
        verifyNoInteractions(service);
    }

    @Test
    void colisNonModifiable_409ProblemDetailAvecCode() throws Exception {
        when(service.changeStatus(any(), any(), any())).thenThrow(new YadonyBusinessException(
                HttpStatus.CONFLICT, "bid-status-not-changeable", "Bid Status Not Changeable", "Colis annulé"));

        mockMvc.perform(post("/admin/bids/{id}/status", BID).contentType("application/json").content(BODY)
                        .with(authentication(auth(AdminRole.SUPER_ADMIN))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("bid-status-not-changeable"));
    }
}
