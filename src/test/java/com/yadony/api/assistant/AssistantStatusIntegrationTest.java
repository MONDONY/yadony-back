package com.yadony.api.assistant;

import com.yadony.api.assistant.llm.LlmRouter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = "yadony.assistant.enabled=true")
class AssistantStatusIntegrationTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AssistantService service;
    @MockitoBean LlmRouter router;

    private static UsernamePasswordAuthenticationToken withRole(String role) {
        return new UsernamePasswordAuthenticationToken("uid-status", null, List.of(new SimpleGrantedAuthority(role)));
    }

    @Test
    void allume200EnabledTrue() throws Exception {
        mockMvc.perform(get("/assistant/status").with(authentication(withRole("ROLE_SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
        verifyNoInteractions(service, router);
    }

    @Test
    void invite403() throws Exception {
        mockMvc.perform(get("/assistant/status").with(authentication(withRole("ROLE_GUEST"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service, router);
    }

    @Test
    void sansJeton401() throws Exception {
        mockMvc.perform(get("/assistant/status")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service, router);
    }
}
