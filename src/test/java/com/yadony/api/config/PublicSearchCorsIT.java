package com.yadony.api.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Le site public yadony.com appelle la recherche d'annonces depuis le navigateur : la
 * pre-requete CORS doit etre acceptee pour yadony.com et www.yadony.com, et refusee pour
 * toute autre origine. La valeur de production se pose par la variable d'environnement
 * {@code CORS_ALLOWED_ORIGINS} (propriete {@code app.cors.allowed-origins}), pas dans le code.
 *
 * <p>{@code @SpringBootTest} + MockMvc : la vraie chaine de filtres tourne, donc le
 * {@code CorsFilter} de {@code SecurityConfig.corsConfigurationSource}.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.cors.allowed-origins=https://yadony.com,https://www.yadony.com")
@DisplayName("CORS de la recherche publique d'annonces")
class PublicSearchCorsIT {

    private static final String PATH = "/public/announcements";

    @Autowired
    MockMvc mockMvc;

    @Test
    @DisplayName("yadony.com : la pre-requete GET est autorisee")
    void preflight_apexOrigin_isAllowed() throws Exception {
        mockMvc.perform(options(PATH)
                        .header(HttpHeaders.ORIGIN, "https://yadony.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://yadony.com"));
    }

    @Test
    @DisplayName("www.yadony.com : la pre-requete GET est autorisee")
    void preflight_wwwOrigin_isAllowed() throws Exception {
        mockMvc.perform(options(PATH)
                        .header(HttpHeaders.ORIGIN, "https://www.yadony.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://www.yadony.com"));
    }

    @Test
    @DisplayName("Une autre origine est refusee (403)")
    void preflight_otherOrigin_isRejected() throws Exception {
        mockMvc.perform(options(PATH)
                        .header(HttpHeaders.ORIGIN, "https://autre-site.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }
}
