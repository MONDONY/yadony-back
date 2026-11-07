package com.yadony.api.admin.broadcast.segment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Segments d'audience enregistrés : CRUD admin sur Postgres embarqué (migration V363). */
@SpringBootTest
@ActiveProfiles("e2e")
@AutoConfigureMockMvc
@DisplayName("AdminBroadcastSegmentControllerIT — /admin/broadcast-segments")
class AdminBroadcastSegmentControllerIT {

    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final String RULE = """
            {"op":"AND","children":[{"field":"kyc","operator":"IN","value":["VERIFIED"]}]}""";

    private static UsernamePasswordAuthenticationToken adminAuth() {
        AdminPrincipal principal = new AdminPrincipal(
                ADMIN_ID, "admin@yadony.test", AdminRole.ADMIN, false, "uid-admin-segment");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("NOTIFICATION_SEND")));
    }

    private static UsernamePasswordAuthenticationToken supportAuth() {
        AdminPrincipal principal = new AdminPrincipal(
                UUID.randomUUID(), "support@yadony.test", AdminRole.SUPPORT, false, "uid-support-segment");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @BeforeEach
    void clean() {
        jdbc.update("UPDATE admin_broadcasts SET segment_id = NULL");
        jdbc.update("DELETE FROM broadcast_segments");
    }

    private static String json(String name, String rule) {
        return "{\"name\":\"" + name + "\",\"description\":\"d\",\"rule\":" + rule + "}";
    }

    private JsonNode create(String name, String rule) throws Exception {
        MvcResult r = mockMvc.perform(post("/admin/broadcast-segments").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(json(name, rule)))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("create puis GET renvoie le compte, la liste renvoie -1")
    void create_puisGet_renvoieLeCompte() throws Exception {
        JsonNode created = create("Voyageurs vérifiés", RULE);
        String id = created.get("id").asText();

        mockMvc.perform(get("/admin/broadcast-segments/" + id).with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Voyageurs vérifiés"))
                .andExpect(jsonPath("$.recipientCount").isNumber())
                .andExpect(jsonPath("$.recipientCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.rule.op").value("AND"));

        mockMvc.perform(get("/admin/broadcast-segments").with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].recipientCount").value(-1));
    }

    @Test
    @DisplayName("nom déjà pris à la casse près → 409, à la création et au renommage")
    void create_nomPrisCasseDifferente_409() throws Exception {
        create("Mon Segment", RULE);

        mockMvc.perform(post("/admin/broadcast-segments").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(json("mon segment", RULE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("broadcast-segment-name-taken")));

        String other = create("Autre", RULE).get("id").asText();
        mockMvc.perform(put("/admin/broadcast-segments/" + other).with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(json("MON SEGMENT", RULE)))
                .andExpect(status().isConflict());
        // Se renommer soi-même en changeant la casse reste permis.
        mockMvc.perform(put("/admin/broadcast-segments/" + other).with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(json("AUTRE", RULE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("AUTRE"));
    }

    @Test
    @DisplayName("règle invalide → 422 problem+json")
    void create_regleInvalide_422ProblemJson() throws Exception {
        mockMvc.perform(post("/admin/broadcast-segments").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("X", "{\"op\":\"AND\",\"children\":[{\"field\":\"inconnu\",\"operator\":\"EQ\",\"value\":\"a\"}]}")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().contentTypeCompatibleWith("application/problem+json"));
        mockMvc.perform(post("/admin/broadcast-segments").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(json("X", "{\"foo\":1}")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("delete (soft) puis GET et PUT → 404, le nom redevient libre")
    void delete_puisGet_404() throws Exception {
        String id = create("A supprimer", RULE).get("id").asText();

        mockMvc.perform(delete("/admin/broadcast-segments/" + id).with(authentication(adminAuth())))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/admin/broadcast-segments/" + id).with(authentication(adminAuth())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("broadcast-segment-not-found")));
        mockMvc.perform(put("/admin/broadcast-segments/" + id).with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(json("Z", RULE)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/admin/broadcast-segments/" + id).with(authentication(adminAuth())))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM broadcast_segments WHERE id = ?::uuid AND deleted_at IS NOT NULL",
                Integer.class, id)).isEqualTo(1);
        create("A supprimer", RULE);
    }

    @Test
    @DisplayName("SUPPORT sans NOTIFICATION_SEND → 403 sur toutes les routes")
    void support_sansAuthority_403() throws Exception {
        String id = UUID.randomUUID().toString();
        mockMvc.perform(get("/admin/broadcast-segments").with(authentication(supportAuth())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/broadcast-segments").with(authentication(supportAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(json("X", RULE)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/broadcast-segments/" + id).with(authentication(supportAuth())))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/admin/broadcast-segments/" + id).with(authentication(supportAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(json("X", RULE)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/admin/broadcast-segments/" + id).with(authentication(supportAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("create/update/delete écrivent l'audit sans le nom, avec ruleHash et conditionCount")
    void create_ecritAuditSansNom() throws Exception {
        String id = create("Nom secret du segment", RULE).get("id").asText();
        mockMvc.perform(put("/admin/broadcast-segments/" + id).with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(json("Nom secret 2", RULE)))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/admin/broadcast-segments/" + id).with(authentication(adminAuth())))
                .andExpect(status().isNoContent());

        List<String> actions = jdbc.queryForList(
                "SELECT action FROM audit_log WHERE entity_type = 'broadcast_segment' AND entity_id = ?::uuid ORDER BY created_at, action",
                String.class, id);
        assertThat(actions).containsExactlyInAnyOrder(
                "BROADCAST_SEGMENT_CREATED", "BROADCAST_SEGMENT_UPDATED", "BROADCAST_SEGMENT_DELETED");
        List<String> payloads = jdbc.queryForList(
                "SELECT CAST(payload AS text) FROM audit_log WHERE entity_type = 'broadcast_segment' AND entity_id = ?::uuid",
                String.class, id);
        for (String p : payloads) {
            assertThat(p).contains("ruleHash").contains("conditionCount").doesNotContain("secret");
        }
    }

    @Test
    @DisplayName("requireRule renvoie la règle ; absent ou supprimé → 404")
    void requireRule() throws Exception {
        String id = create("Pour envoi", RULE).get("id").asText();
        var snap = segmentService.requireRule(UUID.fromString(id));
        assertThat(snap.name()).isEqualTo("Pour envoi");
        assertThat(snap.rule().conditionCount()).isEqualTo(1);
        mockMvc.perform(delete("/admin/broadcast-segments/" + id).with(authentication(adminAuth())))
                .andExpect(status().isNoContent());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> segmentService.requireRule(UUID.fromString(id)))
                .isInstanceOf(com.yadony.api.common.YadonyBusinessException.class);
    }

    @Autowired BroadcastSegmentService segmentService;

    @Test
    @DisplayName("pagination bornée et page négative tolérée")
    void list_bornes() throws Exception {
        mockMvc.perform(get("/admin/broadcast-segments?page=-3&size=5000").with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }
}
