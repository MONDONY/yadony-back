package com.yadony.api.assistant.formdraft;

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

import java.util.List;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Assistant allumé, formulaires IA éteints (valeur par défaut) : 404 et formDraftEnabled false. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = "yadony.assistant.enabled=true")
class FormDraftDisabledIntegrationTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean LlmRouter router;

    private static UsernamePasswordAuthenticationToken asUser() {
        return new UsernamePasswordAuthenticationToken("uid-off", null, List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    @Test
    void eteint404EtStatusFaux() throws Exception {
        mockMvc.perform(post("/assistant/form-draft/package-request").with(authentication(asUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messages\":[{\"role\":\"user\",\"text\":\"x\"}]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("assistant-disabled"));
        mockMvc.perform(get("/assistant/status").with(authentication(asUser())))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.formDraftEnabled").value(false));
        verifyNoInteractions(router);
    }
}
