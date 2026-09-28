package com.yadony.api.cancellation;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.cancellation.dto.AdminNoShowResponse;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Arbitrage admin des no-shows vu de bout en bout (filtre de sécurité, validation, RFC 7807). */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminNoShowControllerIT {

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID ID = UUID.randomUUID();
    private static final String BODY = "{\"reason\":\"Photos horodatées au point de remise\"}";

    @Autowired MockMvc mockMvc;

    @MockitoBean NoShowArbitrationService arbitrationService;
    @MockitoBean AdminNoShowQueryService queryService;

    static UsernamePasswordAuthenticationToken auth(AdminRole role) {
        var principal = new AdminPrincipal(ADMIN_ID, "admin@yadony.com", role, false, "uid");
        var all = new ArrayList<SimpleGrantedAuthority>();
        role.permissions().forEach(p -> all.add(new SimpleGrantedAuthority(p.name())));
        all.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new UsernamePasswordAuthenticationToken(principal, null, all);
    }

    private AdminNoShowResponse response() {
        return new AdminNoShowResponse(ID, UUID.randomUUID(), "HANDOVER", "SENDER_NO_SHOW", "CONFIRMED",
                "CONFIRMED", null, null, null, null, null, null, null, null, null, null, null, null, null,
                "CANCELLED", null, false, false, "CONFIRMED", null, "motif");
    }

    @Test
    void liste_admin_peutTrancher() throws Exception {
        when(queryService.list(isNull(), isNull(), eq(0), eq(20), eq(true)))
                .thenReturn(new PageImpl<>(List.of(response()), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/admin/cancellations").with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(ID.toString()))
                .andExpect(jsonPath("$.content[0].scope").value("HANDOVER"));
    }

    @Test
    void liste_support_sansDisputeResolve_lectureSeule() throws Exception {
        when(queryService.list(eq("CONTESTED"), eq("DELIVERY"), eq(1), eq(10), eq(false)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/admin/cancellations")
                        .param("status", "CONTESTED").param("scope", "DELIVERY")
                        .param("page", "1").param("size", "10")
                        .with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isOk());

        verify(queryService).list("CONTESTED", "DELIVERY", 1, 10, false);
    }

    @Test
    void liste_ancienParametreNoShowStatus_resteCompris() throws Exception {
        when(queryService.list(eq("CONTESTED"), isNull(), anyInt(), anyInt(), anyBoolean()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/admin/cancellations").param("noShowStatus", "CONTESTED")
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isOk());

        verify(queryService).list("CONTESTED", null, 0, 20, true);
    }

    @Test
    void liste_filtreInvalide_422ProblemDetail() throws Exception {
        when(queryService.list(eq("FOO"), any(), anyInt(), anyInt(), anyBoolean()))
                .thenThrow(new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-noshow-filter",
                        "Invalid Filter", "Filtre inconnu"));

        mockMvc.perform(get("/admin/cancellations").param("status", "FOO")
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void confirmer_admin_200_etPropageLAdmin() throws Exception {
        CancellationEntity c = new CancellationEntity();
        when(arbitrationService.confirm(ID, ADMIN_ID, "Photos horodatées au point de remise")).thenReturn(c);
        when(queryService.describe(c, true)).thenReturn(response());

        mockMvc.perform(post("/admin/cancellations/{id}/confirm", ID)
                        .contentType("application/json").content(BODY)
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adminDecision").value("CONFIRMED"));
    }

    @Test
    void rejeter_admin_200() throws Exception {
        CancellationEntity c = new CancellationEntity();
        when(arbitrationService.reject(ID, ADMIN_ID, "Photos horodatées au point de remise")).thenReturn(c);
        when(queryService.describe(c, true)).thenReturn(response());

        mockMvc.perform(post("/admin/cancellations/{id}/reject", ID)
                        .contentType("application/json").content(BODY)
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isOk());

        verify(arbitrationService).reject(ID, ADMIN_ID, "Photos horodatées au point de remise");
    }

    @Test
    void confirmerEtRejeter_support_403() throws Exception {
        mockMvc.perform(post("/admin/cancellations/{id}/confirm", ID)
                        .contentType("application/json").content(BODY)
                        .with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/cancellations/{id}/reject", ID)
                        .contentType("application/json").content(BODY)
                        .with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(arbitrationService);
    }

    @Test
    void motifTropCourtOuTropLong_422() throws Exception {
        mockMvc.perform(post("/admin/cancellations/{id}/reject", ID)
                        .contentType("application/json").content("{\"reason\":\"court\"}")
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/admin/cancellations/{id}/confirm", ID)
                        .contentType("application/json").content("{\"reason\":\"" + "x".repeat(501) + "\"}")
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/admin/cancellations/{id}/confirm", ID)
                        .contentType("application/json").content("{}")
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isUnprocessableEntity());

        verifyNoInteractions(arbitrationService);
    }

    @Test
    void dejaTranchee_409ProblemDetail() throws Exception {
        when(arbitrationService.confirm(any(), any(), any()))
                .thenThrow(new YadonyBusinessException(HttpStatus.CONFLICT, "noshow-already-decided",
                        "No-show Already Decided", "Déjà tranchée"));

        mockMvc.perform(post("/admin/cancellations/{id}/confirm", ID)
                        .contentType("application/json").content(BODY)
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void sansAdminPrincipal_403() throws Exception {
        var auth = new UsernamePasswordAuthenticationToken("uid-x", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("DISPUTE_RESOLVE")));

        mockMvc.perform(post("/admin/cancellations/{id}/confirm", ID)
                        .contentType("application/json").content(BODY)
                        .with(authentication(auth)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(arbitrationService);
    }
}
