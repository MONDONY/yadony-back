package com.yadony.api.tracking;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = "yadony.support.whatsapp-number=")
class ParcelScanControllerSansWhatsappIntegrationTest {

    @Autowired MockMvc mockMvc;

    // Pas de seed : la page inactive d'un UUID inconnu porte déjà le bloc contact.
    @Test
    void numeroVide_boutonWhatsappMasque() throws Exception {
        mockMvc.perform(get("/tracking/{id}/scan", UUID.randomUUID()))
                .andExpect(content().string(not(containsString("wa.me"))))
                .andExpect(content().string(containsString("support@yadony.com")));
    }
}
