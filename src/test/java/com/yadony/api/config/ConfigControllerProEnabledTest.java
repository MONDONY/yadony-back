package com.yadony.api.config;

import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Le feature flag PRO est lu par l'application mobile AVANT toute authentification, au
 * demarrage : la route doit etre publique, et sa forme identique a {@code /config/sms-enabled}
 * pour que le client reutilise le meme parseur.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ConfigControllerProEnabledTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private FirebaseAuth firebaseAuth;

    @Test
    void proEnabledIsPublicAndKeepsTheSmsEnabledShape() throws Exception {
        mockMvc.perform(get("/config/pro-enabled"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").isBoolean());
    }

    @Test
    void proEnabledDefaultsToFalse() throws Exception {
        // Meme defaut que la production : l'offre PRO reste fermee tant qu'un administrateur
        // ne l'ouvre pas depuis le back-office.
        mockMvc.perform(get("/config/pro-enabled"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }
}
