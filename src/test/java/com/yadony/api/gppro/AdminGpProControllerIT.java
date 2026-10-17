package com.yadony.api.gppro;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminGpProControllerIT {

    private static final UUID ADMIN_ID = UUID.randomUUID();

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired GpProApplicationRepository applications;
    @Autowired GpProDocumentRepository documents;
    @Autowired CacheManager cacheManager;

    @MockBean StorageService storage;
    @SpyBean UserRepository spyUsers;

    private UserEntity user;

    @BeforeEach
    void setUp() {
        documents.deleteAll();
        applications.deleteAll();
        // H2 ne connaît pas « FOR NO KEY UPDATE » : le verrou devient une lecture simple.
        doAnswer(inv -> spyUsers.findById(inv.getArgument(0))).when(spyUsers).findByIdForUpdate(any());
        when(storage.generatePresignedUrl(any(), any(Duration.class)))
                .thenAnswer(inv -> "https://r2.test/" + inv.getArgument(0));
        user = new UserEntity();
        user.setFirebaseUid("uid-gppro-admin-" + UUID.randomUUID());
        user.setUsername("gp-" + UUID.randomUUID().toString().substring(0, 8));
        user.setFirstName("Awa");
        user.setLastName("Diallo");
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.VERIFIED);
        user.setRoles(new java.util.HashSet<>(Set.of(Role.TRAVELER)));
        user = userRepository.saveAndFlush(user);
    }

    private GpProApplicationEntity pendingApplication() {
        GpProApplicationEntity a = new GpProApplicationEntity();
        a.setUserId(user.getId());
        a.setStatus(GpProApplicationStatus.PENDING);
        a.setSource(GpProApplicationSource.PROFILE);
        a.setTravelFrequency(GpProTravelFrequency.WEEKLY);
        return applications.saveAndFlush(a);
    }

    private GpProDocumentEntity document(UUID appId, String key) {
        GpProDocumentEntity d = new GpProDocumentEntity();
        d.setApplicationId(appId);
        d.setOwnerId(user.getId());
        d.setDocType(GpProDocumentType.TRAVEL_PROOF);
        d.setStorageKey(key);
        if (key == null) {
            d.setPurgedAt(java.time.LocalDateTime.now());
        }
        return documents.saveAndFlush(d);
    }

    private UsernamePasswordAuthenticationToken adminAuth(AdminRole role) {
        AdminPrincipal principal = new AdminPrincipal(ADMIN_ID, "admin@yadony.test", role, false, "uid-admin-gppro");
        List<SimpleGrantedAuthority> authorities = new ArrayList<>(
                role.permissions().stream().map(p -> new SimpleGrantedAuthority(p.name())).toList());
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    @Test
    void adminWithoutGpProReviewPermissionGets403() throws Exception {
        GpProApplicationEntity app = pendingApplication();
        var support = authentication(adminAuth(AdminRole.SUPPORT));

        mockMvc.perform(get("/admin/gp-pro/applications").with(support)).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/gp-pro/applications/{id}", app.getId()).with(support))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/gp-pro/applications/{id}/approve", app.getId()).with(support))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/users/{id}/gp-pro/grant", user.getId()).with(support)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }

    @Test
    void listShowsPendingApplicationsWithDocumentCount() throws Exception {
        GpProApplicationEntity app = pendingApplication();
        document(app.getId(), "gp_pro/x/a.jpg");

        mockMvc.perform(get("/admin/gp-pro/applications").with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].applicationId").value(app.getId().toString()))
                .andExpect(jsonPath("$.content[0].displayName").value("Awa Diallo"))
                .andExpect(jsonPath("$.content[0].documentCount").value(1));
    }

    @Test
    void detailReturnsPresignedUrlsOnlyForUnpurgedDocuments() throws Exception {
        GpProApplicationEntity app = pendingApplication();
        document(app.getId(), "gp_pro/x/a.jpg");
        document(app.getId(), null);

        mockMvc.perform(get("/admin/gp-pro/applications/{id}", app.getId())
                        .with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kycStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.documents.length()").value(1))
                .andExpect(jsonPath("$.documents[0].url").value("https://r2.test/gp_pro/x/a.jpg"))
                .andExpect(jsonPath("$.documents[0].type").value("TRAVEL_PROOF"))
                .andExpect(jsonPath("$.openDisputes").value(0))
                .andExpect(jsonPath("$.openReports").value(0))
                .andExpect(jsonPath("$.lastFiveRatings.length()").value(0));
        org.mockito.Mockito.verify(storage).generatePresignedUrl(eq("gp_pro/x/a.jpg"), eq(Duration.ofMinutes(15)));
    }

    @Test
    void countersExposeGpProKeys() throws Exception {
        pendingApplication();
        var cache = cacheManager.getCache(com.yadony.api.admin.notifications.AdminNotificationCache.CACHE);
        if (cache != null) {
            cache.clear();
        }

        mockMvc.perform(get("/admin/notifications/counters").with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.gpPro").value(1))
                .andExpect(jsonPath("$.counts.gpProReview").value(0));
        mockMvc.perform(get("/admin/notifications/counters").with(authentication(adminAuth(AdminRole.SUPPORT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.gpPro").doesNotExist())
                .andExpect(jsonPath("$.counts.gpProReview").doesNotExist());
    }

    @Test
    void concurrentApproveOnlyOneSucceeds() throws Exception {
        GpProApplicationEntity app = pendingApplication();
        var admin = authentication(adminAuth(AdminRole.ADMIN));

        mockMvc.perform(post("/admin/gp-pro/applications/{id}/approve", app.getId()).with(admin))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/admin/gp-pro/applications/{id}/approve", app.getId()).with(admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("gp-pro-application-not-pending"));

        UserEntity reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.isGpPro()).isTrue();
        assertThat(applications.findById(app.getId()).orElseThrow().getStatus())
                .isEqualTo(GpProApplicationStatus.APPROVED);
    }

    @Test
    void rejectThenRevokeFlow() throws Exception {
        GpProApplicationEntity app = pendingApplication();
        var admin = authentication(adminAuth(AdminRole.ADMIN));

        mockMvc.perform(post("/admin/gp-pro/applications/{id}/reject", app.getId()).with(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"court\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/admin/gp-pro/applications/{id}/reject", app.getId()).with(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Justificatifs illisibles\"}"))
                .andExpect(status().isNoContent());
        assertThat(applications.findById(app.getId()).orElseThrow().getStatus())
                .isEqualTo(GpProApplicationStatus.REJECTED);

        mockMvc.perform(post("/admin/users/{id}/gp-pro/revoke", user.getId()).with(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Motif suffisant\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/admin/users/{id}/gp-pro/grant", user.getId()).with(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"terrain\"}"))
                .andExpect(status().isNoContent());
        assertThat(userRepository.findById(user.getId()).orElseThrow().isGpPro()).isTrue();
        mockMvc.perform(post("/admin/users/{id}/gp-pro/revoke", user.getId()).with(admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Motif suffisant\"}"))
                .andExpect(status().isNoContent());
        UserEntity reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.isGpPro()).isFalse();
        assertThat(reloaded.getGpProRevokedReason()).isEqualTo("Motif suffisant");
    }
}
