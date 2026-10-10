package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.media.MediaUploadRepository;
import com.yadony.api.common.media.PhotoPurgeRunRepository;
import com.yadony.api.common.media.PhotoPurgeRunner;
import com.yadony.api.common.media.PhotoReconciliationService;
import com.yadony.api.common.media.UploadStats;
import com.yadony.api.config.PlatformSettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("AdminPhotoStorageControllerIT — /admin/storage/photo-purge")
class AdminPhotoStorageControllerIT {

    @Autowired MockMvc mockMvc;

    @MockitoBean PhotoPurgeRunner runner;
    @MockitoBean PhotoReconciliationService reconciliation;
    @MockitoBean PhotoPurgeRunRepository runs;
    @MockitoBean MediaUploadRepository uploads;
    @MockitoBean PlatformSettingsService settings;
    @MockitoBean AuditService audit;

    private static final UUID ADMIN_ID = UUID.randomUUID();

    private static UsernamePasswordAuthenticationToken adminAuth() {
        AdminPrincipal principal = new AdminPrincipal(
                ADMIN_ID, "admin@yadony.test", AdminRole.ADMIN, false, "uid-admin-photos");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("CONFIG_MANAGE")));
    }

    private static UsernamePasswordAuthenticationToken supportAuth() {
        AdminPrincipal principal = new AdminPrincipal(
                UUID.randomUUID(), "support@yadony.test", AdminRole.SUPPORT, false, "uid-support-photos");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @Test
    void status_requiresConfigManage() throws Exception {
        mockMvc.perform(get("/admin/storage/photo-purge/status").with(authentication(supportAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    void run_requiresConfigManage() throws Exception {
        mockMvc.perform(post("/admin/storage/photo-purge/run").with(authentication(supportAuth())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/storage/photo-purge/reconcile").with(authentication(supportAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    void status_returnsSwitchRunsAndStats() throws Exception {
        when(settings.photoPurgeEnabled()).thenReturn(false);
        when(settings.photoRetentionDays()).thenReturn(7);
        when(runner.isRunning()).thenReturn(true);
        when(runs.findTop5ByKindOrderByStartedAtDesc(any())).thenReturn(List.of());
        when(uploads.statsSince(any())).thenReturn(new UploadStats(3, 300), new UploadStats(10, 1000));

        mockMvc.perform(get("/admin/storage/photo-purge/status").with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purgeEnabled").value(false))
                .andExpect(jsonPath("$.retentionDays").value(7))
                .andExpect(jsonPath("$.running").value(true))
                .andExpect(jsonPath("$.lastPurges").isEmpty())
                .andExpect(jsonPath("$.uploads24h").value(3))
                .andExpect(jsonPath("$.bytes24h").value(300))
                .andExpect(jsonPath("$.uploads7d").value(10))
                .andExpect(jsonPath("$.bytes7d").value(1000));
    }

    @Test
    void run_whileRunning_is409() throws Exception {
        when(runner.isRunning()).thenReturn(true);
        mockMvc.perform(post("/admin/storage/photo-purge/run").with(authentication(adminAuth())))
                .andExpect(status().isConflict());
        verify(runner, never()).runPurge(any());
    }

    @Test
    void run_isAccepted_andAudited() throws Exception {
        when(runner.isRunning()).thenReturn(false);
        mockMvc.perform(post("/admin/storage/photo-purge/run").with(authentication(adminAuth())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.started").value(true));
        verify(audit).log(eq("photo_storage"), eq(null), eq("PHOTO_PURGE_TRIGGERED"), eq(ADMIN_ID), any(Map.class));
        verify(runner, timeout(2000)).runPurge(PhotoPurgeRunner.TRIGGER_ADMIN);
    }

    @Test
    void reconcile_defaultsToGuardKept() throws Exception {
        when(runner.isRunning()).thenReturn(false);
        mockMvc.perform(post("/admin/storage/photo-purge/reconcile").with(authentication(adminAuth())))
                .andExpect(status().isAccepted());
        verify(reconciliation, timeout(2000)).reconcile(PhotoPurgeRunner.TRIGGER_ADMIN, false);
    }

    @Test
    void reconcile_allowBulkIsForwardedAndAudited() throws Exception {
        when(runner.isRunning()).thenReturn(false);
        mockMvc.perform(post("/admin/storage/photo-purge/reconcile").param("allowBulk", "true")
                        .with(authentication(adminAuth())))
                .andExpect(status().isAccepted());
        verify(reconciliation, timeout(2000)).reconcile(PhotoPurgeRunner.TRIGGER_ADMIN, true);
        verify(audit).log(eq("photo_storage"), eq(null), eq("PHOTO_RECONCILE_TRIGGERED"), eq(ADMIN_ID),
                org.mockito.ArgumentMatchers.argThat(m -> Boolean.TRUE.equals(m.get("allowBulk"))));
    }

    @Test
    void reconcile_whileRunning_is409() throws Exception {
        when(runner.isRunning()).thenReturn(true);
        mockMvc.perform(post("/admin/storage/photo-purge/reconcile").with(authentication(adminAuth())))
                .andExpect(status().isConflict());
    }
}
