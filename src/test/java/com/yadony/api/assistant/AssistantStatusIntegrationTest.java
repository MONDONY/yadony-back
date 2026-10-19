package com.yadony.api.assistant;

import com.yadony.api.assistant.dto.AssistantScreenResponse;
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
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = {"yadony.assistant.enabled=true", "yadony.assistant.form-draft.enabled=true"})
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
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.formDraftEnabled").value(true));
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

    @Test
    void ecranConnu200SansQuota() throws Exception {
        when(service.screen("/envois", "fr", false)).thenReturn(Optional.of(new AssistantScreenResponse("ecran-mes-colis",
                "L'écran Mes colis", "Vous êtes sur Mes colis.", List.of("Comment annuler ?"), List.of())));
        mockMvc.perform(get("/assistant/screen").param("path", "/envois").param("locale", "fr")
                        .with(authentication(withRole("ROLE_SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.explanation").value("Vous êtes sur Mes colis."))
                .andExpect(jsonPath("$.questions[0]").value("Comment annuler ?"))
                .andExpect(jsonPath("$.explanationRich").doesNotExist());
        verifyNoInteractions(router);
    }

    /** YADONY-FLUTTER-PROD-13 : {@code format=rich} ajoute {@code explanationRich}. */
    @Test
    void ecranFormatRich() throws Exception {
        when(service.screen("/envois", "fr", true)).thenReturn(Optional.of(new AssistantScreenResponse(
                "ecran-mes-colis", "L'écran Mes colis", "Ligne une.\nLigne deux.", List.of(), List.of(),
                "Ligne une.\nLigne deux.")));
        mockMvc.perform(get("/assistant/screen").param("path", "/envois").param("locale", "fr")
                        .param("format", "rich").with(authentication(withRole("ROLE_SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.explanationRich").value("Ligne une.\nLigne deux."));
    }

    @Test
    void ecranInconnu204() throws Exception {
        when(service.screen(anyString(), any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(Optional.empty());
        mockMvc.perform(get("/assistant/screen").param("path", "/inconnu")
                        .with(authentication(withRole("ROLE_SENDER"))))
                .andExpect(status().isNoContent());
    }

    @Test
    void ecranInvite403EtSansJeton401() throws Exception {
        mockMvc.perform(get("/assistant/screen").param("path", "/envois")
                .with(authentication(withRole("ROLE_GUEST")))).andExpect(status().isForbidden());
        mockMvc.perform(get("/assistant/screen").param("path", "/envois")).andExpect(status().isUnauthorized());
    }
}
