package com.yadony.api.maintenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("Mode maintenance — admin, filtre 503, /config/maintenance")
class MaintenanceModeIT {

    private static final UUID ADMIN_ID = UUID.randomUUID();

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired MaintenanceStateRepository stateRepository;
    @Autowired MaintenanceExemptionRepository exemptionRepository;
    @Autowired MaintenanceCache cache;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired org.springframework.security.web.FilterChainProxy filterChainProxy;

    private UserEntity user;
    private UserEntity tester;

    @BeforeEach
    void setUp() {
        reset();
        user = persistUser("Awa");
        tester = persistUser("Testeur");
    }

    /** Jamais laisser la maintenance allumée : le contexte Spring est partagé avec les autres IT. */
    @AfterEach
    void reset() {
        exemptionRepository.deleteAll();
        stateRepository.findById(MaintenanceStateEntity.SINGLETON_ID).ifPresent(s -> {
            s.setEnabled(false);
            stateRepository.save(s);
        });
        cache.evict();
    }

    /** Le filtre doit connaître l'appelant : il tourne APRÈS FirebaseTokenFilter. */
    @Test
    void filtreApresLAuthentification() {
        List<String> names = filterChainProxy.getFilterChains().get(0).getFilters().stream()
                .map(f -> f.getClass().getName()).toList();
        int firebase = -1;
        int maintenance = -1;
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).startsWith(com.yadony.api.auth.FirebaseTokenFilter.class.getName())) firebase = i;
            if (names.get(i).equals(MaintenanceFilter.class.getName())) maintenance = i;
        }
        assertThat(firebase).isNotNegative();
        assertThat(maintenance).isGreaterThan(firebase);
    }

    @Test
    void horsMaintenance_toutPasse_etConfigDitFaux() throws Exception {
        mockMvc.perform(get("/announcements").with(authentication(userAuth(user))))
                .andExpect(notMaintenance());
        mockMvc.perform(get("/config/maintenance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.exempt").value(false));
    }

    @Test
    void activation_bloqueLApi_maisPasLAdminNiLesWebhooksNiLaConfig() throws Exception {
        Instant end = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        mockMvc.perform(put("/admin/maintenance").with(authentication(adminAuth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("enabled", true, "message", "  Mise à jour  ",
                                "estimatedEndAt", end.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.message").value("Mise à jour"))
                .andExpect(jsonPath("$.startedAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedByEmail").doesNotExist());

        mockMvc.perform(get("/announcements").with(authentication(userAuth(user))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", MaintenanceFilter.RETRY_AFTER_SECONDS))
                .andExpect(jsonPath("$.code").value(MaintenanceFilter.PROBLEM_CODE))
                .andExpect(jsonPath("$.detail").value("Mise à jour"))
                .andExpect(jsonPath("$.estimatedEndAt").value(end.toString()));
        // Sans jeton aussi : le filtre précède l'autorisation.
        mockMvc.perform(get("/announcements")).andExpect(status().isServiceUnavailable());

        mockMvc.perform(get("/config/maintenance").with(authentication(userAuth(user))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.message").value("Mise à jour"))
                .andExpect(jsonPath("$.estimatedEndAt").value(end.toString()))
                .andExpect(jsonPath("$.exempt").value(false));
        mockMvc.perform(get("/config/commission-rate")).andExpect(status().isOk());
        mockMvc.perform(get("/admin/maintenance").with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/payments/stripe/webhook").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(notMaintenance());
        mockMvc.perform(post("/pawapay/callbacks/deposits").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(notMaintenance());

        assertThat(auditCount("MAINTENANCE_ENABLED")).isPositive();
    }

    @Test
    void messageVide_texteParDefautTraduit() throws Exception {
        enable(null);
        mockMvc.perform(get("/announcements").with(authentication(userAuth(user))).header("Accept-Language", "fr"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Yadony est en maintenance. Revenez dans quelques instants."));
    }

    @Test
    void compteExempte_passe_etLeSait() throws Exception {
        mockMvc.perform(post("/admin/maintenance/exemptions").with(authentication(adminAuth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("userId", tester.getId().toString(), "note", "Recette"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exemptions.length()").value(1))
                .andExpect(jsonPath("$.exemptions[0].displayName").value("Testeur Diallo"))
                .andExpect(jsonPath("$.exemptions[0].note").value("Recette"));
        // Idempotent.
        mockMvc.perform(post("/admin/maintenance/exemptions").with(authentication(adminAuth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("userId", tester.getId().toString()))))
                .andExpect(jsonPath("$.exemptions.length()").value(1));
        enable("Maintenance");

        mockMvc.perform(get("/announcements").with(authentication(userAuth(tester))))
                .andExpect(notMaintenance());
        mockMvc.perform(get("/config/maintenance").with(authentication(userAuth(tester))))
                .andExpect(jsonPath("$.exempt").value(true));
        mockMvc.perform(get("/announcements").with(authentication(userAuth(user))))
                .andExpect(status().isServiceUnavailable());

        mockMvc.perform(delete("/admin/maintenance/exemptions/{id}", tester.getId())
                        .with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exemptions.length()").value(0));
        mockMvc.perform(get("/announcements").with(authentication(userAuth(tester))))
                .andExpect(status().isServiceUnavailable());
        mockMvc.perform(delete("/admin/maintenance/exemptions/{id}", tester.getId())
                        .with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("admin connecté dans l'app (uid String + marque du filtre) → exempté, et le sait")
    void compteAdminDansLApp_resteExempte() throws Exception {
        enable("Maintenance");

        mockMvc.perform(get("/announcements").with(authentication(userAuth(user)))
                        .requestAttr(AdminPrincipal.ADMIN_ACCOUNT_ATTRIBUTE, Boolean.TRUE))
                .andExpect(notMaintenance());
        mockMvc.perform(get("/config/maintenance").with(authentication(userAuth(user)))
                        .requestAttr(AdminPrincipal.ADMIN_ACCOUNT_ATTRIBUTE, Boolean.TRUE))
                .andExpect(jsonPath("$.exempt").value(true));
        // Sans la marque, le même utilisateur reste bloqué.
        mockMvc.perform(get("/announcements").with(authentication(userAuth(user))))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void desactivation_rouvreLApi() throws Exception {
        enable("Maintenance");
        mockMvc.perform(put("/admin/maintenance").with(authentication(adminAuth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("enabled", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.startedAt").doesNotExist());
        mockMvc.perform(get("/announcements").with(authentication(userAuth(user))))
                .andExpect(notMaintenance());
        assertThat(auditCount("MAINTENANCE_DISABLED")).isPositive();
    }

    @Test
    void validations() throws Exception {
        mockMvc.perform(put("/admin/maintenance").with(authentication(adminAuth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("enabled", true,
                                "estimatedEndAt", Instant.now().minus(1, ChronoUnit.HOURS).toString()))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("maintenance-end-in-past"));
        mockMvc.perform(put("/admin/maintenance").with(authentication(adminAuth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("enabled", true, "message", "x".repeat(301)))))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(put("/admin/maintenance").with(authentication(adminAuth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/admin/maintenance/exemptions").with(authentication(adminAuth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("userId", UUID.randomUUID().toString()))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/announcements").with(authentication(userAuth(user))))
                .andExpect(notMaintenance());
    }

    @Test
    void support_403() throws Exception {
        var support = authentication(adminAuth(AdminRole.SUPPORT));
        mockMvc.perform(get("/admin/maintenance").with(support)).andExpect(status().isForbidden());
        mockMvc.perform(put("/admin/maintenance").with(support).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("enabled", true)))).andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/maintenance/exemptions").with(support).contentType(MediaType.APPLICATION_JSON)
                .content(body(Map.of("userId", user.getId().toString())))).andExpect(status().isForbidden());
        mockMvc.perform(delete("/admin/maintenance/exemptions/{id}", user.getId()).with(support))
                .andExpect(status().isForbidden());
    }

    private void enable(String message) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("enabled", true);
        payload.put("message", message);
        mockMvc.perform(put("/admin/maintenance").with(authentication(adminAuth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON).content(body(payload)))
                .andExpect(status().isOk());
    }

    /** Lecture SQL : le payload JSONB de l'entité ne se relit pas sous H2. */
    private long auditCount(String action) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = ?", Long.class, action);
        return count == null ? 0 : count;
    }

    private static ResultMatcher notMaintenance() {
        return result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(503);
    }

    private String body(Object o) throws Exception {
        return objectMapper.writeValueAsString(o);
    }

    private UsernamePasswordAuthenticationToken adminAuth(AdminRole role) {
        AdminPrincipal principal = new AdminPrincipal(ADMIN_ID, "admin@yadony.test", role, false, "uid-admin-maint");
        List<SimpleGrantedAuthority> authorities = new ArrayList<>(
                role.permissions().stream().map(p -> new SimpleGrantedAuthority(p.name())).toList());
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    private static UsernamePasswordAuthenticationToken userAuth(UserEntity u) {
        return new UsernamePasswordAuthenticationToken(u.getFirebaseUid(), null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    private UserEntity persistUser(String firstName) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-maint-" + UUID.randomUUID());
        u.setFirstName(firstName);
        u.setLastName("Diallo");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        u.setRoles(new java.util.HashSet<>(Set.of(Role.SENDER)));
        u.setTotalTrips(0);
        return userRepository.saveAndFlush(u);
    }
}
