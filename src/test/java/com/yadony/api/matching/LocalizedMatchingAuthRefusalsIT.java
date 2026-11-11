package com.yadony.api.matching;

import com.yadony.api.auth.BlockService;
import com.yadony.api.auth.ConnectedDevicesService;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.GlobalExceptionHandler;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.matching.dto.BidCheckoutRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Paquet 3 du défaut n° 9 : refus réels de {@code matching} (réservation, paiement, statistiques voyageur) et
 * d'{@code auth} (authentification, blocage, appareils), levés par les vraies classes et rendus par le vrai
 * {@code GlobalExceptionHandler} : même statut et même code, texte selon {@code Accept-Language}.
 */
class LocalizedMatchingAuthRefusalsIT {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final com.yadony.api.matching.AnnouncementRepository announcementRepository =
            mock(com.yadony.api.matching.AnnouncementRepository.class);
    private final UserEntity traveler = mock(UserEntity.class);

    private MockMvc mvc;

    @RestController
    class Controllers {
        @GetMapping("/t/content-refused")
        void contentRefused() {
            AnnouncementEntity announcement = mock(AnnouncementEntity.class);
            when(announcement.getRefusedTypes()).thenReturn(List.of("alcool"));
            BidContentRules.assertNotRefused(announcement, "Vêtements, Alcool");
        }

        @GetMapping("/t/checkout")
        void checkout() {
            UserEntity sender = mock(UserEntity.class);
            when(sender.getRoles()).thenReturn(new HashSet<>(Set.of(Role.SENDER)));
            when(sender.getId()).thenReturn(UUID.randomUUID());
            when(userRepository.findByFirebaseUid("uid-sender")).thenReturn(Optional.of(sender));
            AnnouncementEntity closed = mock(AnnouncementEntity.class);
            when(closed.getStatus()).thenReturn(AnnouncementStatus.CANCELLED);
            when(announcementRepository.findByIdForUpdate(any())).thenReturn(Optional.of(closed));
            BidCheckoutRequest request = mock(BidCheckoutRequest.class);
            when(request.announcementId()).thenReturn(UUID.randomUUID());
            new BidCheckoutService(mock(BidRepository.class), announcementRepository, userRepository, null, null, "",
                    null, null, null, null, null).checkout("uid-sender", request, null);
        }

        @GetMapping("/t/stats")
        com.yadony.api.matching.dto.TravelerStatsDto stats() {
            return statsController().getMyStats().getBody();
        }

        @GetMapping("/t/block")
        void block() {
            UUID same = UUID.randomUUID();
            new BlockService(null, null, null, null).block(same, same);
        }

        @GetMapping("/t/device")
        void device() {
            new ConnectedDevicesService(null, null, Optional.empty()).revokeDevice(UUID.randomUUID(), "d1", "d1");
        }
    }

    private TravelerStatsController statsController() {
        return new TravelerStatsController(null, userRepository, null, null, null, null, null, null, null);
    }

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        when(traveler.getRoles()).thenReturn(Set.of(Role.TRAVELER));
        when(traveler.isProAccount()).thenReturn(false);
        mvc = MockMvcBuilders.standaloneSetup(new Controllers())
                .setControllerAdvice(new GlobalExceptionHandler(TestMessages.resolver()))
                .build();
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequestBuilder getJson(String url) {
        return get(url).accept(MediaType.APPLICATION_JSON);
    }

    @Test
    @DisplayName("réservation refusée (type de contenu refusé) : FR par défaut, EN avec Accept-Language: en")
    void bookingRefused_contentType() throws Exception {
        mvc.perform(getJson("/t/content-refused"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("content-type-refused"))
                .andExpect(jsonPath("$.detail").value("Le voyageur n'accepte pas : Alcool"));
        mvc.perform(getJson("/t/content-refused").header("Accept-Language", "en"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("content-type-refused"))
                .andExpect(jsonPath("$.title").value("Content Type Refused"))
                .andExpect(jsonPath("$.detail").value("The traveler does not accept: Alcool"));
    }

    @Test
    @DisplayName("paiement par carte refusé (trajet clos) : FR et EN, statut 409 et code inchangés")
    void checkoutRefused_announcementNotActive() throws Exception {
        mvc.perform(getJson("/t/checkout").header("Accept-Language", "fr-FR,fr;q=0.9"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("announcement-not-active"))
                .andExpect(jsonPath("$.detail").value("Cette annonce n'est plus disponible"));
        mvc.perform(getJson("/t/checkout").header("Accept-Language", "en-US,en;q=0.9"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("announcement-not-active"))
                .andExpect(jsonPath("$.detail").value("This trip is no longer available"));
    }

    @Test
    @DisplayName("statistiques voyageur réservées au PRO : FR et EN")
    void travelerStats_proRequired() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("uid-traveler", "n/a"));
        when(userRepository.findByFirebaseUid("uid-traveler")).thenReturn(Optional.of(traveler));

        mvc.perform(getJson("/t/stats"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("pro-required"))
                .andExpect(jsonPath("$.detail").value("Les statistiques sont réservées aux voyageurs PRO."));
        mvc.perform(getJson("/t/stats").header("Accept-Language", "en"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("pro-required"))
                .andExpect(jsonPath("$.detail").value("Statistics are reserved for PRO travelers."));
    }

    @Test
    @DisplayName("statistiques sans authentification : 401, FR et EN")
    void travelerStats_unauthenticated() throws Exception {
        mvc.perform(getJson("/t/stats"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Authentification requise"));
        mvc.perform(getJson("/t/stats").header("Accept-Language", "en"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthenticated"))
                .andExpect(jsonPath("$.detail").value("Authentication required"));
    }

    @Test
    @DisplayName("blocage de soi-même (auth) : FR et EN, 400 et code inchangés")
    void block_self() throws Exception {
        mvc.perform(getJson("/t/block"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-block"))
                .andExpect(jsonPath("$.detail").value("Action invalide"));
        mvc.perform(getJson("/t/block").header("Accept-Language", "en"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-block"))
                .andExpect(jsonPath("$.detail").value("Invalid action"));
    }

    @Test
    @DisplayName("révocation de l'appareil courant (ResponseStatusException localisée) : FR et EN")
    void revokeCurrentDevice() throws Exception {
        mvc.perform(getJson("/t/device"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Impossible de révoquer l'appareil courant"));
        mvc.perform(getJson("/t/device").header("Accept-Language", "en"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Cannot revoke the current device"));
    }
}
