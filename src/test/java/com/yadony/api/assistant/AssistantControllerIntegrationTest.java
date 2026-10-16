package com.yadony.api.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.assistant.dto.AssistantAskRequest;
import com.yadony.api.assistant.dto.AssistantHistoryTurn;
import com.yadony.api.assistant.dto.ParsedAnswer;
import com.yadony.api.assistant.llm.LlmResult;
import com.yadony.api.assistant.llm.LlmRouter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = {"yadony.assistant.enabled=true", "yadony.assistant.daily-quota=2"})
class AssistantControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean LlmRouter router;

    private static UsernamePasswordAuthenticationToken asUser(String uid) {
        return new UsernamePasswordAuthenticationToken(uid, null, List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    private String body(String question) throws Exception {
        return objectMapper.writeValueAsString(new AssistantAskRequest(question, "fr", List.of()));
    }

    @Test
    void repondAvecUneAction() throws Exception {
        when(router.complete(anyString(), anyString(), any())).thenReturn(Optional.of(new LlmResult<>("groq",
                new ParsedAnswer("Allez dans Moi.", true, List.of("/profile/edit/phone")))));
        mockMvc.perform(post("/assistant/ask").with(authentication(asUser("uid-ok")))
                        .contentType(MediaType.APPLICATION_JSON).content(body("Où je change mon numéro ?")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answered").value(true))
                .andExpect(jsonPath("$.actions[0].route").value("/profile/edit/phone"))
                .andExpect(jsonPath("$.provider").value("groq"))
                .andExpect(jsonPath("$.remainingToday").value(1));
    }

    @Test
    void sansJeton401() throws Exception {
        mockMvc.perform(post("/assistant/ask").contentType(MediaType.APPLICATION_JSON).content(body("q")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void questionVideOuTropLongue422() throws Exception {
        mockMvc.perform(post("/assistant/ask").with(authentication(asUser("uid-422")))
                        .contentType(MediaType.APPLICATION_JSON).content(body(" ")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations.question").exists());
        mockMvc.perform(post("/assistant/ask").with(authentication(asUser("uid-422")))
                        .contentType(MediaType.APPLICATION_JSON).content(body("x".repeat(501))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void principalAdminSansUidFirebase403AvantToutQuota() throws Exception {
        AdminPrincipal principal = new AdminPrincipal(UUID.randomUUID(), "admin@yadony.test", AdminRole.ADMIN, false,
                "uid-admin-assistant");
        UsernamePasswordAuthenticationToken admin = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        mockMvc.perform(post("/assistant/ask").with(authentication(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(body("numéro")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("assistant-user-required"));
        verifyNoInteractions(router);
    }

    @Test
    void uidVide403() throws Exception {
        mockMvc.perform(post("/assistant/ask").with(authentication(asUser("")))
                        .contentType(MediaType.APPLICATION_JSON).content(body("numéro")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("assistant-user-required"));
    }

    @Test
    void invite403() throws Exception {
        UsernamePasswordAuthenticationToken guest = new UsernamePasswordAuthenticationToken("uid-guest", null,
                List.of(new SimpleGrantedAuthority("ROLE_GUEST")));
        mockMvc.perform(post("/assistant/ask").with(authentication(guest))
                        .contentType(MediaType.APPLICATION_JSON).content(body("numéro")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(router);
    }

    @Test
    void tourDHistoriqueDe4001Caracteres422() throws Exception {
        String json = objectMapper.writeValueAsString(new AssistantAskRequest("numéro", "fr",
                List.of(new AssistantHistoryTurn("user", "x".repeat(4001)))));
        mockMvc.perform(post("/assistant/ask").with(authentication(asUser("uid-history")))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations").exists());
        verifyNoInteractions(router);
    }

    @Test
    void historiqueDeCinquanteEtUnTours422() throws Exception {
        List<AssistantHistoryTurn> turns = new ArrayList<>();
        for (int i = 0; i < 51; i++) turns.add(new AssistantHistoryTurn("user", "t"));
        String json = objectMapper.writeValueAsString(new AssistantAskRequest("numéro", "fr", turns));
        mockMvc.perform(post("/assistant/ask").with(authentication(asUser("uid-history")))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void tourDHistoriqueDe4000CaracteresAccepte() throws Exception {
        when(router.complete(anyString(), anyString(), any())).thenReturn(Optional.empty());
        String json = objectMapper.writeValueAsString(new AssistantAskRequest("numéro", "fr",
                List.of(new AssistantHistoryTurn("user", "x".repeat(4000)))));
        mockMvc.perform(post("/assistant/ask").with(authentication(asUser("uid-history-ok")))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
    }

    @Test
    void quotaDepasse429() throws Exception {
        when(router.complete(anyString(), anyString(), any())).thenReturn(Optional.empty());
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/assistant/ask").with(authentication(asUser("uid-quota")))
                    .contentType(MediaType.APPLICATION_JSON).content(body("numéro"))).andExpect(status().isOk());
        }
        mockMvc.perform(post("/assistant/ask").with(authentication(asUser("uid-quota")))
                        .contentType(MediaType.APPLICATION_JSON).content(body("numéro")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("assistant-quota-exceeded"))
                .andExpect(jsonPath("$.retryAfter").exists());
    }
}
