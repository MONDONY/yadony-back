package com.yadony.api.dac7;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Endpoints admin DAC7 par HTTP (contexte de test partagé, H2). Le calcul (SQL PostgreSQL) est couvert par
 * {@code Dac7ExportPostgresIT} ; ici : droit {@code DAC7_REPORT}, CORS de l'export, registre, décision, relance.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("Admin DAC7 : droit DAC7_REPORT, registre, décision, relance, CORS")
class Dac7AdminControllerIT {

    private static final UUID ADMIN_ID = UUID.randomUUID();

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    private UserEntity traveler;

    @BeforeEach
    void setUp() {
        UserEntity u = new UserEntity();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        u.setFirebaseUid("uid-dac7-admin-" + suffix);
        u.setUsername("dac7a-" + suffix);
        u.setFirstName("Awa");
        u.setLastName("Diallo");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.VERIFIED);
        u.setRoles(Set.of(Role.TRAVELER));
        u.setCountry("FR");
        traveler = users.saveAndFlush(u);
    }

    private static RequestPostProcessor admin(AdminRole role) {
        AdminPrincipal principal = new AdminPrincipal(ADMIN_ID, "admin@yadony.test", role, false, "uid-admin-dac7");
        List<SimpleGrantedAuthority> authorities = new ArrayList<>(
                role.permissions().stream().map(p -> new SimpleGrantedAuthority(p.name())).toList());
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return authentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    private List<MockHttpServletRequestBuilder> everyEndpoint() {
        UUID id = traveler.getId();
        return List.of(
                get("/admin/dac7/overview").param("year", "2026"),
                get("/admin/dac7/sellers").param("year", "2026"),
                get("/admin/dac7/sellers/{id}/diligence", id),
                post("/admin/dac7/sellers/{id}/remind", id),
                post("/admin/dac7/sellers/{id}/decision", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"x\"}"),
                get("/admin/dac7/refunds").param("year", "2026"),
                get("/admin/dac7/anomalies").param("year", "2026"),
                post("/admin/dac7/exports").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"year\":2026,\"mode\":\"FULL\"}"),
                get("/admin/dac7/exports/control.csv").param("year", "2026"),
                post("/admin/dac7/statements/send").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"year\":2026}"));
    }

    @Test
    @DisplayName("SUPPORT : 403 partout ; sans jeton : 401")
    void supportInterdit() throws Exception {
        for (MockHttpServletRequestBuilder request : everyEndpoint()) {
            mockMvc.perform(request.with(admin(AdminRole.SUPPORT))).andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/admin/dac7/overview").param("year", "2026")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dac7_due_diligence_log WHERE user_id = ?",
                Integer.class, traveler.getId())).isZero();
    }

    @Test
    @DisplayName("ADMIN : décision inscrite au registre avec l'auteur, audit sans la note")
    void decision() throws Exception {
        mockMvc.perform(post("/admin/dac7/sellers/{id}/decision", traveler.getId()).with(admin(AdminRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Voyageur joint par téléphone, il complète cette semaine\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/admin/dac7/sellers/{id}/diligence", traveler.getId()).with(admin(AdminRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("TEAM_DECISION"))
                .andExpect(jsonPath("$[0].channel").value("ADMIN"))
                .andExpect(jsonPath("$[0].note").value("Voyageur joint par téléphone, il complète cette semaine"))
                .andExpect(jsonPath("$[0].at").exists());
        assertThat(jdbc.queryForObject("SELECT actor_id FROM dac7_due_diligence_log WHERE user_id = ?", UUID.class,
                traveler.getId())).isEqualTo(ADMIN_ID);
        List<String> audits = jdbc.queryForList("SELECT CAST(payload AS VARCHAR) FROM audit_log WHERE entity_id = ? "
                + "AND action = 'DAC7_TEAM_DECISION'", String.class, traveler.getId());
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)).contains("noteLength").doesNotContain("téléphone");
    }

    @Test
    @DisplayName("décision : note vide ou trop longue 422, compte inconnu 404")
    void decisionInvalide() throws Exception {
        mockMvc.perform(post("/admin/dac7/sellers/{id}/decision", traveler.getId()).with(admin(AdminRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"   \"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("dac7-note-invalid")));
        mockMvc.perform(post("/admin/dac7/sellers/{id}/decision", traveler.getId()).with(admin(AdminRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"" + "a".repeat(501) + "\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/admin/dac7/sellers/{id}/decision", UUID.randomUUID()).with(admin(AdminRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"ok\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("relance d'un voyageur jamais payé : 409 dac7-not-solicitable, rien écrit")
    void relanceNonSollicitable() throws Exception {
        mockMvc.perform(post("/admin/dac7/sellers/{id}/remind", traveler.getId()).with(admin(AdminRole.ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("dac7-not-solicitable")));
        assertThat(users.findById(traveler.getId()).orElseThrow().getTaxInfoRequestedAt()).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dac7_due_diligence_log WHERE user_id = ?",
                Integer.class, traveler.getId())).isZero();
    }

    @Test
    @DisplayName("export : requête invalide 422 avant tout calcul ; année hors bornes 422")
    void exportInvalide() throws Exception {
        mockMvc.perform(post("/admin/dac7/exports").with(admin(AdminRole.ADMIN))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"year\":2026,\"mode\":\"TOUT\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/admin/dac7/overview").param("year", "2019").with(admin(AdminRole.ADMIN)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("dac7-year-invalid")));
    }

    @Test
    @DisplayName("CORS : le navigateur de l'admin peut lire Content-Disposition, X-Dac7-Excluded et X-Dac7-Encrypted")
    void corsEnTetesExposes() throws Exception {
        mockMvc.perform(get("/admin/dac7/sellers/{id}/diligence", traveler.getId()).with(admin(AdminRole.ADMIN))
                        .header(HttpHeaders.ORIGIN, "http://localhost:3000"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                        org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.containsString("Content-Disposition"),
                                org.hamcrest.Matchers.containsString("X-Dac7-Excluded"),
                                org.hamcrest.Matchers.containsString("X-Dac7-Encrypted"),
                                org.hamcrest.Matchers.containsString("X-Dac7-Warnings"),
                                org.hamcrest.Matchers.containsString("X-Dac7-Run-Id"))));
    }

    @Test
    @DisplayName("dépôt confirmé : 404 si inconnu, 204 idempotent, statut DEPOSITED, audit sans donnée ; SUPPORT 403 ; "
            + "année non close 409")
    void depot() throws Exception {
        // Ordre et année FIXES, hors de portée des autres tests du contexte partagé (jamais de collision aléatoire) :
        // 2025 est close, le fichier généré « maintenant » peut être confirmé déposé.
        jdbc.update("DELETE FROM dac7_export_runs WHERE reporting_year IN (2025, 2026) AND sequence = 998");
        UUID run = UUID.randomUUID();
        jdbc.update("INSERT INTO dac7_export_runs (id, reporting_year, sequence, export_mode, status, file_name, "
                + "sellers, generated_at) VALUES (?, 2025, 998, 'PARTIAL', 'GENERATED', 'f.xml', 2, CURRENT_TIMESTAMP)",
                run);
        // Fichier de l'année en cours : montants partiels, jamais confirmé déposé.
        UUID open = UUID.randomUUID();
        int currentYear = java.time.LocalDate.now(java.time.ZoneOffset.UTC).getYear();
        jdbc.update("INSERT INTO dac7_export_runs (id, reporting_year, sequence, export_mode, status, file_name, "
                + "sellers, generated_at) VALUES (?, ?, 998, 'PARTIAL', 'GENERATED', 'g.xml', 2, CURRENT_TIMESTAMP)",
                open, currentYear);
        mockMvc.perform(post("/admin/dac7/exports/{id}/deposited", open).with(admin(AdminRole.ADMIN)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("dac7-year-not-closed"));
        mockMvc.perform(post("/admin/dac7/exports/{id}/deposited", UUID.randomUUID()).with(admin(AdminRole.ADMIN)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/admin/dac7/exports/{id}/deposited", run).with(admin(AdminRole.SUPPORT)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/dac7/exports/{id}/deposited", run).with(admin(AdminRole.ADMIN)))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/admin/dac7/exports/{id}/deposited", run).with(admin(AdminRole.ADMIN)))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT status FROM dac7_export_runs WHERE id = ?", String.class, run))
                .isEqualTo("DEPOSITED");
        List<String> audits = jdbc.queryForList("SELECT CAST(payload AS VARCHAR) FROM audit_log WHERE entity_id = ? "
                + "AND action = 'DAC7_EXPORT_MARKED_DEPOSITED'", String.class, run);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0)).contains("sellers").doesNotContain("Awa");
    }
}
