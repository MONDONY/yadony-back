package com.yadony.api.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.notifications.NotificationDispatcher;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Envoi par règle et par segment de bout en bout sur Postgres embarqué : historique figé et audit sans PII. */
@SpringBootTest
@ActiveProfiles("e2e")
@AutoConfigureMockMvc
@DisplayName("AdminBroadcastRuleFlowIT — envoi par règle / segment")
class AdminBroadcastRuleFlowIT {

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
    @MockitoBean NotificationDispatcher notificationDispatcher;

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final String RULE_A = """
            {"op":"AND","children":[{"field":"kyc","operator":"IN","value":["VERIFIED"]}]}""";
    private static final String RULE_B = """
            {"op":"AND","children":[{"field":"kyc","operator":"IN","value":["REJECTED"]}]}""";

    private static UsernamePasswordAuthenticationToken adminAuth() {
        AdminPrincipal principal = new AdminPrincipal(
                ADMIN_ID, "admin@yadony.test", AdminRole.ADMIN, false, "uid-admin-flow");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("NOTIFICATION_SEND")));
    }

    @Test
    @DisplayName("send_rule_historiseLaRegleEtLeSegment : modifier le segment ne change pas l'historique")
    void send_rule_historiseLaRegleEtLeSegment() throws Exception {
        String created = mockMvc.perform(post("/admin/broadcast-segments").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Flux " + UUID.randomUUID() + "\",\"rule\":" + RULE_A + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode seg = objectMapper.readTree(created);
        String segId = seg.get("id").asText();
        String segName = seg.get("name").asText();

        mockMvc.perform(post("/admin/notifications/broadcast").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T\",\"body\":\"B\",\"target\":{\"type\":\"RULE\",\"segmentId\":\"" + segId + "\"}}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.segmentName").value(segName))
                .andExpect(jsonPath("$.audienceRule.children[0].value[0]").value("VERIFIED"));

        mockMvc.perform(put("/admin/broadcast-segments/" + segId).with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renommé " + UUID.randomUUID() + "\",\"rule\":" + RULE_B + "}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/notifications/broadcasts?size=100").with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.segmentId=='" + segId + "')].audienceRule.children[0].value[0]")
                        .value("VERIFIED"))
                .andExpect(jsonPath("$.content[?(@.segmentId=='" + segId + "')].segmentName").value(segName));
    }

    @Test
    @DisplayName("send_rule_auditSansNomNiVille : seulement l'empreinte et le nombre de conditions")
    void send_rule_auditSansNomNiVille() throws Exception {
        String rule = "{\"op\":\"AND\",\"children\":[{\"field\":\"corridor_interest\",\"operator\":\"MATCHES\","
                + "\"value\":{\"kind\":\"CITY\",\"origin\":\"Villeneuve-Zorglub\",\"destination\":\"Dakar\","
                + "\"sources\":[\"PUBLISHED\"]}}]}";
        String res = mockMvc.perform(post("/admin/notifications/broadcast").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T\",\"body\":\"B\",\"target\":{\"type\":\"RULE\",\"rule\":" + rule + "}}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(res).get("id").asText();

        String payload = jdbc.queryForObject(
                "SELECT payload::text FROM audit_log WHERE action = 'BROADCAST_SENT' AND entity_id = ?::uuid",
                String.class, id);
        assertThat(payload).contains("ruleHash").contains("conditionCount");
        assertThat(payload).doesNotContain("Villeneuve").doesNotContain("Dakar");
    }
}
