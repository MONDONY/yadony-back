package com.yadony.api.matching;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * La page publique d'une annonce est le point d'arrivée du lien imprimé sur
 * l'affiche que le voyageur poste sur ses propres canaux. Les tests vérifient
 * qu'elle est bien accessible <em>sans authentification</em> : c'est toute sa
 * raison d'être, la quasi-totalité des visiteurs n'ayant pas encore de compte.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PublicAnnouncementPageControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired AnnouncementRepository announcementRepository;

    private AnnouncementEntity persistAnnouncement(AnnouncementStatus status) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(UUID.randomUUID());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setHandoverDeadline(LocalDateTime.now().plusDays(9));
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
    void activeAnnouncement_isServedWithoutAuthentication() throws Exception {
        AnnouncementEntity a = persistAnnouncement(AnnouncementStatus.ACTIVE);

        mockMvc.perform(get("/public/annonce/" + a.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("public/annonce"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Paris")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Dakar")));
    }

    /**
     * Le bouton d'ouverture doit porter le schéma applicatif, et l'URL de partage
     * doit rester la forme {@code https://} servie ici. C'est cette stabilité qui
     * permettra d'activer les App Links plus tard sans invalider les affiches
     * déjà publiées sur Facebook.
     */
    @Test
    void activeAnnouncement_exposesDeepLinkAndCanonicalShareUrl() throws Exception {
        AnnouncementEntity a = persistAnnouncement(AnnouncementStatus.ACTIVE);

        mockMvc.perform(get("/public/annonce/" + a.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("dony://annonce/" + a.getId())))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("/public/annonce/" + a.getId())));
    }

    @Test
    void eachView_incrementsTheAttributionCounter() throws Exception {
        AnnouncementEntity a = persistAnnouncement(AnnouncementStatus.ACTIVE);
        assertThat(a.getShareViewCount()).isZero();

        mockMvc.perform(get("/public/annonce/" + a.getId())).andExpect(status().isOk());
        mockMvc.perform(get("/public/annonce/" + a.getId())).andExpect(status().isOk());

        AnnouncementEntity reloaded = announcementRepository.findById(a.getId()).orElseThrow();
        assertThat(reloaded.getShareViewCount()).isEqualTo(2L);
    }

    @Test
    void cancelledAnnouncement_rendersUnavailableStateInsteadOfTheTrip() throws Exception {
        AnnouncementEntity a = persistAnnouncement(AnnouncementStatus.CANCELLED);

        mockMvc.perform(get("/public/annonce/" + a.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("n'est plus disponible")));
    }

    @Test
    void draftAnnouncement_isNotExposedPublicly() throws Exception {
        AnnouncementEntity a = persistAnnouncement(AnnouncementStatus.DRAFT);

        mockMvc.perform(get("/public/annonce/" + a.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("n'est plus disponible")));
    }

    @Test
    void unknownAnnouncement_rendersUnavailableState() throws Exception {
        mockMvc.perform(get("/public/annonce/" + UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("n'est plus disponible")));
    }

    /**
     * Un lien tronqué au copier-coller depuis un post Facebook produit un
     * identifiant illisible. Il doit retomber sur la page « indisponible », pas
     * sur une 500 : la personne vient d'un canal public, elle n'a pas à voir
     * une erreur serveur.
     */
    @Test
    void malformedIdentifier_doesNotFailTheRequest() throws Exception {
        mockMvc.perform(get("/public/annonce/pas-un-uuid"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("n'est plus disponible")));
    }

    @Test
    void unavailablePage_doesNotIncrementTheAttributionCounter() throws Exception {
        AnnouncementEntity a = persistAnnouncement(AnnouncementStatus.CANCELLED);

        mockMvc.perform(get("/public/annonce/" + a.getId())).andExpect(status().isOk());

        AnnouncementEntity reloaded = announcementRepository.findById(a.getId()).orElseThrow();
        assertThat(reloaded.getShareViewCount()).isZero();
    }
}
