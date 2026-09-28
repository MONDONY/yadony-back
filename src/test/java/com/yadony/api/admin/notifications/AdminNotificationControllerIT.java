package com.yadony.api.admin.notifications;

import com.yadony.api.admin.AdminAlertEntity;
import com.yadony.api.admin.AdminAlertRepository;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.admin.account.AdminUserEntity;
import com.yadony.api.admin.account.AdminUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("AdminNotificationController : contrat HTTP de la cloche")
class AdminNotificationControllerIT {

    @Autowired MockMvc mockMvc;
    @Autowired AdminUserRepository adminUsers;
    @Autowired AdminAlertRepository alerts;
    @Autowired CacheManager cacheManager;
    @Autowired JdbcTemplate jdbc;

    private AdminUserEntity admin;

    @BeforeEach
    void setUp() {
        cacheManager.getCache(AdminNotificationCache.CACHE).clear();
        admin = adminUsers.save(new AdminUserEntity("uid-bell-" + UUID.randomUUID(),
                "bell-" + UUID.randomUUID() + "@yadony.test", AdminRole.SUPPORT));
    }

    private UsernamePasswordAuthenticationToken as(String... permissions) {
        List<SimpleGrantedAuthority> granted = new ArrayList<>();
        granted.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (String p : permissions) {
            granted.add(new SimpleGrantedAuthority(p));
        }
        return new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(admin.getId(), admin.getEmail(), admin.getRole(), false, admin.getFirebaseUid()),
                null, granted);
    }

    @Test
    void feed_formeJson() throws Exception {
        AdminAlertEntity alert = new AdminAlertEntity();
        alert.setType("PAYOUT_FAILED_" + UUID.randomUUID());
        alert.setSeverity("CRITICAL");
        alerts.save(alert);

        mockMvc.perform(get("/admin/notifications/feed").param("limit", "100").with(authentication(as("ALERT_VIEW"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[?(@.id == 'ADMIN_ALERT:" + alert.getId() + "')].type").value("ADMIN_ALERT"))
                .andExpect(jsonPath("$.items[?(@.id == 'ADMIN_ALERT:" + alert.getId() + "')].severity").value("CRITICAL"))
                .andExpect(jsonPath("$.items[?(@.id == 'ADMIN_ALERT:" + alert.getId() + "')].title").value("Alerte plateforme"))
                .andExpect(jsonPath("$.items[?(@.id == 'ADMIN_ALERT:" + alert.getId() + "')].link").value("/alertes"))
                .andExpect(jsonPath("$.items[0].createdAt").isString())
                .andExpect(jsonPath("$.items[0].summary").isString())
                .andExpect(jsonPath("$.unreadCount").isNumber())
                .andExpect(jsonPath("$.unreadCapped").isBoolean())
                .andExpect(jsonPath("$.lastSeenAt").isString());
    }

    @Test
    void feed_sansPermissionDeLecture_estVideMaisAccessible() throws Exception {
        mockMvc.perform(get("/admin/notifications/feed").with(authentication(as())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.unreadCount").value(0));
    }

    @Test
    void feed_curseurInvalide_estRefuseEnProblemDetail() throws Exception {
        mockMvc.perform(get("/admin/notifications/feed").param("before", "hier").with(authentication(as("ALERT_VIEW"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void feed_curseurIso_estAccepte() throws Exception {
        mockMvc.perform(get("/admin/notifications/feed").param("before", "2026-09-28T10:00:00Z")
                        .with(authentication(as("ALERT_VIEW"))))
                .andExpect(status().isOk());
    }

    @Test
    void counters_formeJson_clesSelonPermissions() throws Exception {
        mockMvc.perform(get("/admin/notifications/counters")
                        .with(authentication(as("SUPPORT_TICKET_VIEW", "REPORT_VIEW"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.support").isNumber())
                .andExpect(jsonPath("$.counts.reports").isNumber())
                .andExpect(jsonPath("$.counts.heldPayouts").doesNotExist())
                .andExpect(jsonPath("$.counts.alerts").doesNotExist())
                .andExpect(jsonPath("$.unreadCount").isNumber());

        mockMvc.perform(get("/admin/notifications/counters").with(authentication(as(
                        "SUPPORT_TICKET_VIEW", "REPORT_VIEW", "DISPUTE_VIEW", "USER_KYC", "PAYMENT_VIEW",
                        "USER_GDPR_DELETE", "ALERT_VIEW"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.incidents").isNumber())
                .andExpect(jsonPath("$.counts.kyc").isNumber())
                .andExpect(jsonPath("$.counts.heldPayouts").isNumber())
                .andExpect(jsonPath("$.counts.walletRefunds").isNumber())
                .andExpect(jsonPath("$.counts.gdpr").isNumber())
                .andExpect(jsonPath("$.counts.alerts").isNumber());
    }

    @Test
    void markSeen_204_poseLaDate_neReculeJamais_sansAudit() throws Exception {
        long auditBefore = auditRows();
        mockMvc.perform(post("/admin/notifications/mark-seen").with(authentication(as())))
                .andExpect(status().isNoContent());
        LocalDateTime first = adminUsers.findNotificationsSeenAt(admin.getId()).orElseThrow();

        mockMvc.perform(post("/admin/notifications/mark-seen").with(authentication(as()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upTo\":\"2020-01-01T00:00:00Z\"}"))
                .andExpect(status().isNoContent());
        assertThat(adminUsers.findNotificationsSeenAt(admin.getId())).contains(first);

        mockMvc.perform(get("/admin/notifications/feed").with(authentication(as())))
                .andExpect(jsonPath("$.lastSeenAt").isString());
        assertThat(auditRows()).isEqualTo(auditBefore);
    }

    private long auditRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE actor_id = ?", Long.class, admin.getId());
    }

    @Test
    void markSeen_corpsVide_vautMaintenant_etDatesEnUtcZ() throws Exception {
        mockMvc.perform(post("/admin/notifications/mark-seen").with(authentication(as()))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNoContent());
        assertThat(adminUsers.findNotificationsSeenAt(admin.getId())).isPresent();

        AdminAlertEntity alert = new AdminAlertEntity();
        alert.setType("ESCROW_J48");
        alerts.save(alert);
        mockMvc.perform(get("/admin/notifications/feed").with(authentication(as("ALERT_VIEW"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastSeenAt").value(org.hamcrest.Matchers.endsWith("Z")))
                .andExpect(jsonPath("$.items[0].createdAt").value(org.hamcrest.Matchers.endsWith("Z")))
                .andExpect(jsonPath("$.items[0].link").value(org.hamcrest.Matchers.startsWith("/")));
    }

    @Test
    void anonyme_401() throws Exception {
        mockMvc.perform(get("/admin/notifications/feed")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/notifications/counters")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/admin/notifications/mark-seen")).andExpect(status().isUnauthorized());
    }

    @Test
    void utilisateurNonAdmin_403() throws Exception {
        UsernamePasswordAuthenticationToken user = new UsernamePasswordAuthenticationToken(
                "firebase-user", null, List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
        mockMvc.perform(get("/admin/notifications/feed").with(authentication(user))).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/notifications/counters").with(authentication(user))).andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/notifications/mark-seen").with(authentication(user))).andExpect(status().isForbidden());
    }

    @Test
    void roleAdminSansPrincipalAdmin_403() throws Exception {
        UsernamePasswordAuthenticationToken odd = new UsernamePasswordAuthenticationToken(
                "not-a-principal", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        mockMvc.perform(get("/admin/notifications/feed").with(authentication(odd))).andExpect(status().isForbidden());
    }
}
