package com.yadony.api.auth;

import com.yadony.api.auth.dto.ProfilePublicResponse;
import com.yadony.api.common.YadonyBusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ProfilePublicControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean  ProfilePublicService profilePublicService;

    private static final UUID TARGET_USER_ID = UUID.randomUUID();
    private static final String SENDER_UID = "uid-sender-profile";

    private static UsernamePasswordAuthenticationToken asRole(String uid, String role) {
        return new UsernamePasswordAuthenticationToken(
                uid, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private ProfilePublicResponse stubProfile() {
        return new ProfilePublicResponse(
                TARGET_USER_ID.toString(),
                "Moussa D.",
                null,
                true,
                false,
                false,
                12,
                new BigDecimal("4.80"),
                10,
                "Membre depuis mars 2025",
                List.of(),
                null,
                null,
                null,
                null);
    }

    @Test
    void getProfilePublic_notFound_returns404() throws Exception {
        when(profilePublicService.getProfilePublic(any(), any()))
                .thenThrow(new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "user-not-found", "Not Found", "Utilisateur introuvable"));

        mockMvc.perform(get("/users/{userId}/profile-public", TARGET_USER_ID)
                        .with(authentication(asRole(SENDER_UID, "SENDER"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void getProfilePublic_hiddenByBlock_returns404NotForbidden() throws Exception {
        // Le service masque un compte bloqué par un 404 « not-found » : le contrôleur doit
        // le relayer tel quel. Un 403 confirmerait l'existence du profil et rendrait le
        // blocage détectable.
        when(profilePublicService.getProfilePublic(eq(TARGET_USER_ID), any()))
                .thenThrow(new YadonyBusinessException(
                        HttpStatus.NOT_FOUND, "not-found", "Not Found", "Ressource introuvable"));

        mockMvc.perform(get("/users/{userId}/profile-public", TARGET_USER_ID)
                        .with(authentication(asRole(SENDER_UID, "SENDER"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not-found"));
    }

    @Test
    void getProfilePublic_asAnonymous_returns401() throws Exception {
        mockMvc.perform(get("/users/{userId}/profile-public", TARGET_USER_ID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getProfilePublic_asSender_returns200() throws Exception {
        when(profilePublicService.getProfilePublic(eq(TARGET_USER_ID), any())).thenReturn(stubProfile());

        mockMvc.perform(get("/users/{userId}/profile-public", TARGET_USER_ID)
                        .with(authentication(asRole(SENDER_UID, "SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(TARGET_USER_ID.toString()))
                .andExpect(jsonPath("$.displayName").value("Moussa D."))
                .andExpect(jsonPath("$.kycVerified").value(true))
                .andExpect(jsonPath("$.completedBidsCount").value(12))
                .andExpect(jsonPath("$.averageRating").value(4.80))
                .andExpect(jsonPath("$.ratingCount").value(10))
                .andExpect(jsonPath("$.memberSince").value("Membre depuis mars 2025"));
    }

    @Test
    void getProfilePublic_neverExposesPhone() throws Exception {
        when(profilePublicService.getProfilePublic(eq(TARGET_USER_ID), any())).thenReturn(stubProfile());

        MvcResult result = mockMvc.perform(get("/users/{userId}/profile-public", TARGET_USER_ID)
                        .with(authentication(asRole(SENDER_UID, "SENDER"))))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertThat(responseBody).doesNotContain("phone");
        assertThat(responseBody).doesNotContain("phoneNumber");
    }
}
