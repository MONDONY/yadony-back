package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.common.AuditService;
import com.yadony.api.signalements.ReportEntity;
import com.yadony.api.signalements.ReportReason;
import com.yadony.api.signalements.ReportRepository;
import com.yadony.api.signalements.ReportTargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Restauration de signalements : permission REPORT_DELETE, corps facultatif, contrat bulk. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminReportsRestoreIT {

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID REPORT_ID = UUID.randomUUID();

    @Autowired MockMvc mockMvc;

    @MockitoBean ReportRepository reportRepository;
    @MockitoBean AuditService auditService;

    private UsernamePasswordAuthenticationToken auth(AdminRole role) {
        var principal = new AdminPrincipal(ADMIN_ID, "admin@yadony.com", role, false, "uid");
        var all = new ArrayList<SimpleGrantedAuthority>();
        role.permissions().forEach(p -> all.add(new SimpleGrantedAuthority(p.name())));
        all.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new UsernamePasswordAuthenticationToken(principal, null, all);
    }

    private ReportEntity deleted() {
        ReportEntity r = new ReportEntity();
        ReflectionTestUtils.setField(r, "id", REPORT_ID);
        r.setTargetType(ReportTargetType.APP);
        r.setReason(ReportReason.values()[0]);
        r.softDelete();
        return r;
    }

    @Test
    @DisplayName("POST restore — ADMIN avec motif : 200 et motif audite")
    void restore_withReason_returns200() throws Exception {
        when(reportRepository.findAllByIdIncludingDeleted(List.of(REPORT_ID))).thenReturn(List.of(deleted()));

        mockMvc.perform(post("/admin/reports/{id}/restore", REPORT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Supprime par erreur\"}")
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(REPORT_ID.toString()))
                .andExpect(jsonPath("$.deletedAt").doesNotExist());

        verify(auditService).log(eq("REPORT"), eq(REPORT_ID), eq("REPORT_RESTORED"), eq(ADMIN_ID),
                eq(Map.of("reportId", REPORT_ID.toString(), "mode", "single", "reason", "Supprime par erreur")));
    }

    @Test
    @DisplayName("POST restore — sans corps : toléré, 200")
    void restore_withoutBody_returns200() throws Exception {
        when(reportRepository.findAllByIdIncludingDeleted(List.of(REPORT_ID))).thenReturn(List.of(deleted()));

        mockMvc.perform(post("/admin/reports/{id}/restore", REPORT_ID)
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST restore — motif présent mais trop court : 422")
    void restore_shortReason_returns422() throws Exception {
        mockMvc.perform(post("/admin/reports/{id}/restore", REPORT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"oups\"}")
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isUnprocessableEntity());

        verify(reportRepository, never()).findAllByIdIncludingDeleted(any());
    }

    @Test
    @DisplayName("POST restore — le support (sans REPORT_DELETE) : 403")
    void restore_asSupport_isForbidden() throws Exception {
        mockMvc.perform(post("/admin/reports/{id}/restore", REPORT_ID)
                        .with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isForbidden());

        verify(reportRepository, never()).findAllByIdIncludingDeleted(any());
    }

    @Test
    @DisplayName("POST bulk-restore — { ids } seul : { restored, skipped }")
    void bulkRestore_returnsCounts() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(reportRepository.findAllByIdIncludingDeleted(any())).thenReturn(List.of(deleted()));

        mockMvc.perform(post("/admin/reports/bulk-restore")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"" + REPORT_ID + "\",\"" + unknown + "\"]}")
                        .with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restored").value(1))
                .andExpect(jsonPath("$.skipped").value(1));
    }

    @Test
    @DisplayName("POST bulk-restore — le support : 403")
    void bulkRestore_asSupport_isForbidden() throws Exception {
        mockMvc.perform(post("/admin/reports/bulk-restore")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"" + REPORT_ID + "\"]}")
                        .with(authentication(auth(AdminRole.SUPPORT))))
                .andExpect(status().isForbidden());
    }
}
