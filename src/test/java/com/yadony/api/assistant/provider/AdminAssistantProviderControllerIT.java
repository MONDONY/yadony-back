package com.yadony.api.assistant.provider;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.assistant.llm.LlmCallException;
import com.yadony.api.assistant.llm.LlmClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminAssistantProviderControllerIT {

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final String URL = "/admin/assistant/providers";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean LlmClients client;

    @BeforeEach
    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM assistant_llm_providers");
    }

    private UsernamePasswordAuthenticationToken adminAuth(AdminRole role) {
        AdminPrincipal principal = new AdminPrincipal(ADMIN_ID, "admin@yadony.test", role, false, "uid-admin-assistant");
        List<SimpleGrantedAuthority> authorities = new ArrayList<>(
                role.permissions().stream().map(p -> new SimpleGrantedAuthority(p.name())).toList());
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    private String createdId(String name) throws Exception {
        String body = """
                {"name":"%s","baseUrl":"https://api.groq.com/openai/v1","model":"llama","apiKey":"gsk_secret_1234",
                 "supportsJsonMode":true,"dailyRequestLimit":1000,"enabled":true}""".formatted(name);
        String json = mockMvc.perform(post(URL).with(authentication(adminAuth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).get("id").asText();
    }

    @Test
    void supportEstRefuse() throws Exception {
        mockMvc.perform(get(URL).with(authentication(adminAuth(AdminRole.SUPPORT))))
                .andExpect(status().isForbidden());
    }

    @Test
    void creeListeEtNeRenvoieJamaisLaCle() throws Exception {
        String body = """
                {"name":"Groq","baseUrl":"https://api.groq.com/openai/v1","model":"llama","apiKey":"gsk_secret_1234",
                 "supportsJsonMode":true,"dailyRequestLimit":1000,"enabled":true}""";
        mockMvc.perform(post(URL).with(authentication(adminAuth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("groq"))
                .andExpect(jsonPath("$.apiKeyLast4").value("1234"))
                .andExpect(jsonPath("$.apiKey").doesNotExist());
        mockMvc.perform(get(URL).with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stats.requestsToday").value(0))
                .andExpect(content().string(not(containsString("gsk_secret"))));
    }

    @Test
    void validation422() throws Exception {
        mockMvc.perform(post(URL).with(authentication(adminAuth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"baseUrl\":\"https://a.com\",\"model\":\"m\",\"apiKey\":\"court\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations.name").exists())
                .andExpect(jsonPath("$.violations.apiKey").exists());
        mockMvc.perform(post(URL).with(authentication(adminAuth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"baseUrl\":\"http://a.com\",\"model\":\"m\",\"apiKey\":\"cle-secrete\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations.baseUrl").exists());
    }

    @Test
    void inconnu404() throws Exception {
        mockMvc.perform(delete(URL + "/" + UUID.randomUUID()).with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("assistant-provider-not-found"));
    }

    @Test
    void modifieReordonneTesteEtSupprime() throws Exception {
        String a = createdId("Groq");
        String b = createdId("Mistral");

        mockMvc.perform(patch(URL + "/" + a).with(authentication(adminAuth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Groq rapide","baseUrl":"https://api.groq.com/openai/v1","model":"llama-2",
                                 "supportsJsonMode":true,"enabled":true}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Groq rapide"))
                .andExpect(jsonPath("$.dailyRequestLimit").doesNotExist())
                .andExpect(jsonPath("$.apiKeyLast4").value("1234"));

        mockMvc.perform(patch(URL + "/" + a).with(authentication(adminAuth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Groq","baseUrl":"https://autre-serveur.com/v1","model":"llama",
                                 "supportsJsonMode":true,"enabled":true}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations.apiKey").exists());

        mockMvc.perform(put(URL + "/order").with(authentication(adminAuth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"" + b + "\",\"" + a + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].slug").value("mistral"))
                .andExpect(jsonPath("$[1].priority").value(2));

        mockMvc.perform(put(URL + "/order").with(authentication(adminAuth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"" + a + "\"]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations.ids").exists());

        when(client.complete(any(), anyString(), anyString())).thenReturn("{\"answer\":\"ok\"}");
        mockMvc.perform(post(URL + "/" + a + "/test").with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        when(client.complete(any(), anyString(), anyString())).thenThrow(new LlmCallException(401, "HTTP 401"));
        mockMvc.perform(post(URL + "/" + a + "/test").with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("HTTP 401"));

        mockMvc.perform(delete(URL + "/" + a).with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(URL).with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void sansAuthentificationRefuse401() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void cleVideDansLeFormulaireDEditionGardeLaCle() throws Exception {
        String id = createdId("Groq");
        mockMvc.perform(patch(URL + "/" + id).with(authentication(adminAuth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Groq","baseUrl":"https://api.groq.com/openai/v1","model":"llama-3",
                                 "apiKey":"","supportsJsonMode":true,"dailyRequestLimit":1000,"enabled":true}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKeyLast4").value("1234"))
                .andExpect(jsonPath("$.model").value("llama-3"));
        mockMvc.perform(patch(URL + "/" + id).with(authentication(adminAuth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Groq","baseUrl":"https://api.groq.com/openai/v1","model":"llama-3",
                                 "apiKey":"   ","supportsJsonMode":true,"dailyRequestLimit":1000,"enabled":true}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKeyLast4").value("1234"));
    }

    @Test
    void cleInvalideRefusee422() throws Exception {
        String id = createdId("Groq");
        String patchBody = """
                {"name":"Groq","baseUrl":"https://api.groq.com/openai/v1","model":"llama",
                 "apiKey":"%s","supportsJsonMode":true,"dailyRequestLimit":1000,"enabled":true}""";
        for (String bad : new String[]{"abc def ghi", "court", "cle-avec-\\u00e9-accent", "abcdefgh\\r\\nijklmnop"}) {
            mockMvc.perform(patch(URL + "/" + id).with(authentication(adminAuth(AdminRole.ADMIN)))
                            .contentType(MediaType.APPLICATION_JSON).content(patchBody.formatted(bad)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.violations.apiKey").exists());
        }
        mockMvc.perform(post(URL).with(authentication(adminAuth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"baseUrl\":\"https://a.com\",\"model\":\"m\",\"apiKey\":\"abcdefgh\\r\\nijklmnop\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations.apiKey").exists());
        mockMvc.perform(post(URL).with(authentication(adminAuth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"baseUrl\":\"https://a.com\",\"model\":\"m\",\"apiKey\":\"  \"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations.apiKey").exists());
    }
}
