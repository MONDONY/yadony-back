package com.yadony.api.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /auth/me/publish-commitments vit sous le permitAll de /auth/** : SecurityConfig doit
 * l'exclure explicitement (authenticatedNonGuest), sans quoi une requête sans jeton ou un
 * invité atteindrait le contrôleur, qui écrit dans users et audit_log.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PublishCommitmentSecurityIT {

    private static final String PATH = "/auth/me/publish-commitments";
    private static final String BODY = """
            {"role":"TRAVELER","version":"2026-10"}
            """;

    @Autowired MockMvc mvc;
    @MockBean PublishCommitmentService service;

    @Test
    void sansAuthentification_GET_retourne401() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void sansAuthentification_PUT_retourne401() throws Exception {
        mvc.perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void invite_PUT_retourne403() throws Exception {
        var guest = new UsernamePasswordAuthenticationToken(
                "uid-guest", null, List.of(new SimpleGrantedAuthority("ROLE_GUEST")));
        mvc.perform(put(PATH).with(authentication(guest))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}
