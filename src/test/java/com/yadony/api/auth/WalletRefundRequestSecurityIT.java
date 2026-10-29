package com.yadony.api.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /auth/me/wallet-refund-request déplace de l'argent et vit sous le permitAll de /auth/** :
 * SecurityConfig doit l'exclure explicitement (authenticatedNonGuest), sans quoi seule une
 * garde du contrôleur, retirable par un refactor, protégerait l'endpoint.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class WalletRefundRequestSecurityIT {

    private static final String PATH = "/auth/me/wallet-refund-request";

    @Autowired MockMvc mvc;
    @MockitoBean AuthService authService;

    @Test
    void sansAuthentification_retourne401() throws Exception {
        mvc.perform(post(PATH)).andExpect(status().isUnauthorized());
        verifyNoInteractions(authService);
    }

    @Test
    void invite_retourne403() throws Exception {
        var guest = new UsernamePasswordAuthenticationToken(
                "uid-guest", null, List.of(new SimpleGrantedAuthority("ROLE_GUEST")));
        mvc.perform(post(PATH).with(authentication(guest))).andExpect(status().isForbidden());
        verifyNoInteractions(authService);
    }
}
