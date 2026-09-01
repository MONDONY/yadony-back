package com.yadony.api.auth;

import com.yadony.api.auth.dto.PublicTravelerProfileResponse;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PublicTravelerControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean ProfilePublicService profilePublicService;

    private static final UUID TARGET = UUID.randomUUID();

    @Test
    void publicProfile_isAccessibleWithoutAuth() throws Exception {
        when(profilePublicService.getPublicTravelerProfile(eq(TARGET), any())).thenReturn(
                new PublicTravelerProfileResponse("Moussa D.", true, true, 12,
                        new BigDecimal("4.80"), 10, "Membre depuis mars 2025", List.of()));

        mockMvc.perform(get("/public/travelers/{id}", TARGET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Moussa D."))
                .andExpect(jsonPath("$.isKiloPro").value(true))
                .andExpect(jsonPath("$.averageRating").value(4.80));
    }

    @Test
    void publicProfile_hiddenByBlock_returns404() throws Exception {
        // Un utilisateur connecté qui ouvre le lien partageable d'un compte bloqué doit
        // recevoir le même 404 que sur le profil interne : le lien public ne doit pas
        // devenir la porte dérobée du blocage.
        when(profilePublicService.getPublicTravelerProfile(eq(TARGET), any()))
                .thenThrow(new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "not-found", "Not Found", "Ressource introuvable"));

        mockMvc.perform(get("/public/travelers/{id}", TARGET))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not-found"));
    }

    @Test
    void publicProfile_returns404WhenUserUnknown() throws Exception {
        when(profilePublicService.getPublicTravelerProfile(any(), any())).thenThrow(
                new YadonyBusinessException(HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));

        mockMvc.perform(get("/public/travelers/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
