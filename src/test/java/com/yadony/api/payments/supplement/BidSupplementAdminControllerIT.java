package com.yadony.api.payments.supplement;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.supplement.dto.AdminSupplementDecisionResponse;
import com.yadony.api.payments.supplement.dto.AdminSupplementRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Décision admin sur un supplément vue de bout en bout : sécurité, validation, RFC 7807. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class BidSupplementAdminControllerIT {

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID ID = UUID.randomUUID();
    private static final UUID BID_ID = UUID.randomUUID();
    private static final String BODY = "{\"reason\":\"Partage du litige : part voyageur\"}";

    @Autowired MockMvc mockMvc;
    @MockitoBean BidSupplementAdminService service;

    static UsernamePasswordAuthenticationToken auth(AdminRole role) {
        var principal = new AdminPrincipal(ADMIN_ID, "admin@yadony.com", role, false, "uid");
        var all = new ArrayList<SimpleGrantedAuthority>();
        role.permissions().forEach(p -> all.add(new SimpleGrantedAuthority(p.name())));
        all.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new UsernamePasswordAuthenticationToken(principal, null, all);
    }

    private static AdminSupplementRow row(String status) {
        return new AdminSupplementRow(ID, BID_ID, UUID.randomUUID(), UUID.randomUUID(), "STRIPE", status, "EUR",
                new BigDecimal("20.00"), new BigDecimal("1.10"), new BigDecimal("21.10"), "GRID_ITEM", null, null, null,
                null, null, null, null, null, null, "pi_1", "ch_1", null, null, null);
    }

    @Test
    void liste_lectureAutoriseeAuSupport() throws Exception {
        when(service.listForBid(BID_ID)).thenReturn(List.of(row("PAID")));

        mockMvc.perform(get("/admin/bids/{id}/supplements", BID_ID).with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ID.toString()))
                .andExpect(jsonPath("$[0].netAmount").value(20.00));
    }

    @Test
    void verser_admin_200_etPropageLAdminEtLaRaison() throws Exception {
        when(service.payout(ID, ADMIN_ID, "Partage du litige : part voyageur"))
                .thenReturn(new AdminSupplementDecisionResponse(true, row("PAID_OUT")));

        mockMvc.perform(post("/admin/supplements/{id}/payout", ID).with(authentication(auth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acted").value(true))
                .andExpect(jsonPath("$.supplement.status").value("PAID_OUT"));
    }

    @Test
    void rembourser_admin_200() throws Exception {
        when(service.refund(ID, ADMIN_ID, "Partage du litige : part voyageur"))
                .thenReturn(new AdminSupplementDecisionResponse(false, row("REFUND_PENDING")));

        mockMvc.perform(post("/admin/supplements/{id}/refund", ID).with(authentication(auth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acted").value(false));
    }

    @Test
    void decision_support_403() throws Exception {
        mockMvc.perform(post("/admin/supplements/{id}/payout", ID).with(authentication(auth(AdminRole.SUPPORT)))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/supplements/{id}/refund", ID).with(authentication(auth(AdminRole.SUPPORT)))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verify(service, never()).payout(any(), any(), anyString());
        verify(service, never()).refund(any(), any(), anyString());
    }

    @Test
    void decision_sansAuthentification_refusee() throws Exception {
        mockMvc.perform(post("/admin/supplements/{id}/payout", ID)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().is4xxClientError());
        verify(service, never()).payout(any(), any(), anyString());
    }

    @Test
    void decision_sansRaison_422() throws Exception {
        mockMvc.perform(post("/admin/supplements/{id}/refund", ID).with(authentication(auth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" \"}"))
                .andExpect(status().isUnprocessableEntity());
        verify(service, never()).refund(any(), any(), anyString());
    }

    @Test
    void decision_refuseeParLeService_problemDetail() throws Exception {
        when(service.refund(ID, ADMIN_ID, "Partage du litige : part voyageur"))
                .thenThrow(new YadonyBusinessException(HttpStatus.CONFLICT, "supplement-already-paid-out",
                        "Supplement Already Paid Out", "déjà versé"));

        mockMvc.perform(post("/admin/supplements/{id}/refund", ID).with(authentication(auth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }
}
