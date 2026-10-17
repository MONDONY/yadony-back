package com.yadony.api.assistant;

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
import com.yadony.api.assistant.llm.LlmRouter;

import java.util.List;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = "yadony.assistant.enabled=false")
class AssistantControllerDisabledIntegrationTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AssistantService service;
    @MockitoBean LlmRouter router;

    @Test
    void eteint404() throws Exception {
        mockMvc.perform(post("/assistant/ask")
                        .with(authentication(new UsernamePasswordAuthenticationToken("uid", null,
                                List.of(new SimpleGrantedAuthority("ROLE_SENDER")))))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"q\",\"locale\":\"fr\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("assistant-disabled"));
    }

    @Test
    void statusEteintRepond200EnabledFalseEtJamais404() throws Exception {
        mockMvc.perform(get("/assistant/status")
                        .with(authentication(new UsernamePasswordAuthenticationToken("uid", null,
                                List.of(new SimpleGrantedAuthority("ROLE_SENDER"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        verifyNoInteractions(service, router);
    }

    @Test
    void ecranEteint404() throws Exception {
        mockMvc.perform(get("/assistant/screen").param("path", "/envois")
                        .with(authentication(new UsernamePasswordAuthenticationToken("uid", null,
                                List.of(new SimpleGrantedAuthority("ROLE_SENDER"))))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("assistant-disabled"));
        verifyNoInteractions(service, router);
    }
}
