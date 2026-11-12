package com.yadony.api.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GuestSessionTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("uid", null, List.of(new SimpleGrantedAuthority(role))));
    }

    @Test
    void exactAddressOrNull_invite_renvoieNull() {
        loginAs("ROLE_GUEST");
        assertThat(GuestSession.exactAddressOrNull("12 rue de la Paix, Paris")).isNull();
    }

    @Test
    void exactAddressOrNull_inscrit_renvoieLAdresse() {
        loginAs("ROLE_SENDER");
        assertThat(GuestSession.exactAddressOrNull("12 rue de la Paix, Paris")).isEqualTo("12 rue de la Paix, Paris");
        loginAs("ROLE_TRAVELER");
        assertThat(GuestSession.exactAddressOrNull("x")).isEqualTo("x");
    }

    @Test
    void exactAddressOrNull_horsRequete_renvoieLAdresse() {
        assertThat(GuestSession.exactAddressOrNull("x")).isEqualTo("x");
    }

    @Test
    void exactAddressOrNull_valeurNulle_resteNulle() {
        loginAs("ROLE_SENDER");
        assertThat(GuestSession.<String>exactAddressOrNull(null)).isNull();
    }
}
