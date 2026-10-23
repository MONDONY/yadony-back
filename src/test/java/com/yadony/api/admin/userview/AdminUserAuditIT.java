package com.yadony.api.admin.userview;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.admin.account.AdminUserEntity;
import com.yadony.api.admin.account.AdminUserRepository;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Journal des gestes admin tracés sur un compte (entité USER). */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminUserAuditIT {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AdminUserRepository adminUserRepository;
    @Autowired JdbcTemplate jdbc;

    private UserEntity user;
    private UserEntity other;
    private AdminUserEntity admin;

    @BeforeEach
    void setUp() {
        user = saveUser();
        other = saveUser();
        admin = adminUserRepository.save(new AdminUserEntity("fb-" + UUID.randomUUID(),
                "agent-" + UUID.randomUUID() + "@yadony.com", AdminRole.ADMIN));
    }

    private UserEntity saveUser() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.NOT_STARTED);
        return userRepository.save(u);
    }

    private static UsernamePasswordAuthenticationToken authWith(AdminRole role, String... permissions) {
        var all = new ArrayList<SimpleGrantedAuthority>();
        all.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
        for (String p : permissions) all.add(new SimpleGrantedAuthority(p));
        var principal = new AdminPrincipal(UUID.randomUUID(), "admin@yadony.com", role, false, "uid");
        return new UsernamePasswordAuthenticationToken(principal, null, all);
    }

    private static UsernamePasswordAuthenticationToken fullAuth() {
        return authWith(AdminRole.ADMIN, "USER_VIEW", "AUDIT_VIEW");
    }

    /** Insère directement (audit_log est immuable en UPDATE) pour maîtriser created_at et l'IP. */
    private long audit(String entityType, UUID entityId, String action, UUID actorId, String payloadJson,
                       LocalDateTime at) {
        jdbc.update("""
                INSERT INTO audit_log (entity_type, entity_id, action, actor_id, payload, ip_address, created_at)
                VALUES (?, ?, ?, ?, ? FORMAT JSON, '203.0.113.9', ?)
                """, entityType, entityId, action, actorId, payloadJson, at);
        return jdbc.queryForObject("SELECT MAX(id) FROM audit_log", Long.class);
    }

    @Test
    void audit_returnsUserTargetedEntriesNewestFirst() throws Exception {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        long older = audit("USER", user.getId(), "USER_SUSPENDED_BY_ADMIN", admin.getId(),
                "{\"reason\":\"fraude\"}", now.minusDays(2));
        long newer = audit("USER", user.getId(), "USER_UNSUSPENDED", admin.getId(), "{}", now.minusDays(1));
        audit("USER", other.getId(), "USER_BANNED_BY_ADMIN", admin.getId(), "{}", now);
        audit("kyc_verification", user.getId(), "KYC_X", admin.getId(), "{}", now);

        mockMvc.perform(get("/admin/users/{id}/audit", user.getId()).with(authentication(fullAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(newer))
                .andExpect(jsonPath("$.content[0].action").value("USER_UNSUSPENDED"))
                .andExpect(jsonPath("$.content[0].reason").doesNotExist())
                .andExpect(jsonPath("$.content[1].id").value(older))
                .andExpect(jsonPath("$.content[1].reason").value("fraude"))
                .andExpect(jsonPath("$.content[1].actorId").value(admin.getId().toString()));
    }

    @Test
    void audit_resolvesAdminNamesFromAdminUsers() throws Exception {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        audit("USER", user.getId(), "USER_SUSPENDED_BY_ADMIN", admin.getId(), "{}", now.minusMinutes(3));
        audit("USER", user.getId(), "USER_AUTO_SUSPENDED", user.getId(), "{}", now.minusMinutes(2));
        audit("USER", user.getId(), "SOMETHING", UUID.randomUUID(), "{}", now.minusMinutes(1));
        audit("USER", user.getId(), "SYSTEM", null, "{}", now);
        String expectedSelf = com.yadony.api.common.MatchingTextUtil.buildName(user);

        mockMvc.perform(get("/admin/users/{id}/audit", user.getId()).with(authentication(fullAuth())))
                .andExpect(jsonPath("$.content[3].actorName").value(admin.getEmail()))
                .andExpect(jsonPath("$.content[2].actorName").value(expectedSelf))
                .andExpect(jsonPath("$.content[1].actorName").doesNotExist())
                .andExpect(jsonPath("$.content[0].actorName").doesNotExist())
                .andExpect(jsonPath("$.content[0].actorId").doesNotExist());
    }

    @Test
    void audit_isPaginated() throws Exception {
        LocalDateTime t = LocalDateTime.now(ZoneOffset.UTC).minusDays(1);
        long a = audit("USER", user.getId(), "A", admin.getId(), "{}", t);
        long b = audit("USER", user.getId(), "B", admin.getId(), "{}", t);
        long old = audit("USER", user.getId(), "OLD", admin.getId(), "{}", t.minusDays(5));

        // Dates égales : l'identifiant décroissant départage.
        mockMvc.perform(get("/admin/users/{id}/audit?page=0&size=2", user.getId())
                        .with(authentication(fullAuth())))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(Math.max(a, b)))
                .andExpect(jsonPath("$.content[1].id").value(Math.min(a, b)));
        mockMvc.perform(get("/admin/users/{id}/audit?page=1&size=2", user.getId())
                        .with(authentication(fullAuth())))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(old));
        mockMvc.perform(get("/admin/users/{id}/audit?size=100000", user.getId())
                        .with(authentication(fullAuth())))
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void audit_doesNotExposeIpAddress() throws Exception {
        audit("USER", user.getId(), "USER_SUSPENDED_BY_ADMIN", admin.getId(), "{}", LocalDateTime.now(ZoneOffset.UTC));

        mockMvc.perform(get("/admin/users/{id}/audit", user.getId()).with(authentication(fullAuth())))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("203.0.113.9"))))
                .andExpect(content().string(not(containsString("ipAddress"))))
                .andExpect(content().string(not(containsString("ip_address"))));
    }

    @Test
    void audit_supportWithoutAuditView_returns403() throws Exception {
        mockMvc.perform(get("/admin/users/{id}/audit", user.getId())
                        .with(authentication(authWith(AdminRole.SUPPORT, "USER_VIEW"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void audit_adminWithAuditView_returns200() throws Exception {
        mockMvc.perform(get("/admin/users/{id}/audit", user.getId()).with(authentication(fullAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void audit_unknownUser_returns404() throws Exception {
        mockMvc.perform(get("/admin/users/{id}/audit", UUID.randomUUID()).with(authentication(fullAuth())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("user-not-found"));
    }
}
