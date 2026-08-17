package com.yadony.api.matching;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Valeurs telles qu'elles ont réellement été saisies dans l'interface GitHub
 * (Settings &gt; Environments &gt; staging &gt; Variables), et non telles
 * qu'on les imaginait.
 *
 * <p>Deux saisies naturelles, deux bugs visibles en production :
 *
 * <ul>
 *   <li>{@code STORE_URL_ANDROID=null} — l'interface GitHub <em>refuse</em>
 *       d'enregistrer une variable vide, on y écrit donc « null » pour dire
 *       « pas encore de valeur ». Mais {@code "null"} n'est pas vide : le
 *       gabarit affichait un bouton « Télécharger Yadony » pointant sur
 *       {@code href="null"}, et sur la page « trajet indisponible » c'était le
 *       bouton principal qui était mort.</li>
 *   <li>{@code PUBLIC_BASE_URL=" https://yadony.com"} — une espace de tête,
 *       invisible à la relecture, qui produisait un {@code og:url} invalide
 *       dans la carte de partage Facebook.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "app.public-base-url= https://yadony.com",
        "app.store.android=null",
        "app.store.ios=null",
        "app.store.os-redirect-enabled=false"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PublicAnnouncementPageControllerSloppyEnvTest {

    private static final String BROWSER_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15";

    @Autowired MockMvc mockMvc;
    @Autowired AnnouncementRepository announcementRepository;

    private AnnouncementEntity persistMinimalAnnouncement(AnnouncementStatus status) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(UUID.randomUUID());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Paris 18e");
        a.setPickupLat(new BigDecimal("48.8920"));
        a.setPickupLng(new BigDecimal("2.3550"));
        a.setDeliveryAddressLabel("Dakar Plateau");
        a.setDeliveryLat(new BigDecimal("14.6930"));
        a.setDeliveryLng(new BigDecimal("-17.4470"));
        a.setAvailableKg(new BigDecimal("12.00"));
        a.setTotalKg(new BigDecimal("23.00"));
        a.setPricePerKg(new BigDecimal("8.00"));
        a.setStatus(status);
        return announcementRepository.saveAndFlush(a);
    }

    @Test
    void literalNullStoreUrl_neverRendersADeadButton() throws Exception {
        AnnouncementEntity a = persistMinimalAnnouncement(AnnouncementStatus.ACTIVE);

        mockMvc.perform(get("/public/annonce/" + a.getId()).header("User-Agent", BROWSER_UA))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("href=\"null\""))))
                .andExpect(content().string(not(containsString("Télécharger Yadony"))));
    }

    /** Même piège sur la page d'indisponibilité, où c'est le bouton principal. */
    @Test
    void literalNullStoreUrl_neverRendersADeadButtonOnTheUnavailablePage() throws Exception {
        AnnouncementEntity a = persistMinimalAnnouncement(AnnouncementStatus.CANCELLED);

        mockMvc.perform(get("/public/annonce/" + a.getId()).header("User-Agent", BROWSER_UA))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("n'est plus disponible")))
                .andExpect(content().string(not(containsString("href=\"null\""))));
    }

    @Test
    void leadingSpaceInPublicBaseUrl_isTrimmedFromTheCanonicalUrl() throws Exception {
        AnnouncementEntity a = persistMinimalAnnouncement(AnnouncementStatus.ACTIVE);

        mockMvc.perform(get("/public/annonce/" + a.getId()).header("User-Agent", BROWSER_UA))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString("content=\"https://yadony.com/annonce/" + a.getId() + "\"")))
                .andExpect(content().string(not(containsString("content=\" https://"))));
    }
}
