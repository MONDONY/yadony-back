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

    /** Navigateur ordinaire : le controleur n'incremente pas pour les robots d'apercu. */
    private static final String BROWSER_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15";

    @Autowired MockMvc mockMvc;
    @Autowired AnnouncementRepository announcementRepository;

    private AnnouncementEntity persistAnnouncement(AnnouncementStatus status) {
        return persistAnnouncement(status, null, false);
    }

    private AnnouncementEntity persistAnnouncement(AnnouncementStatus status,
                                                   UUID linkedPackageRequestId,
                                                   boolean surplusPublished) {
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
        a.setLinkedPackageRequestId(linkedPackageRequestId);
        a.setSurplusPublished(surplusPublished);
        return announcementRepository.saveAndFlush(a);
    }

    @Test
    void activeAnnouncement_isServedWithoutAuthentication() throws Exception {
        AnnouncementEntity a = persistAnnouncement(AnnouncementStatus.ACTIVE);

        mockMvc.perform(get("/public/annonce/" + a.getId()).header("User-Agent", BROWSER_UA))
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

        mockMvc.perform(get("/public/annonce/" + a.getId()).header("User-Agent", BROWSER_UA))
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

        mockMvc.perform(get("/public/annonce/" + a.getId()).header("User-Agent", BROWSER_UA)).andExpect(status().isOk());
        mockMvc.perform(get("/public/annonce/" + a.getId()).header("User-Agent", BROWSER_UA)).andExpect(status().isOk());

        AnnouncementEntity reloaded = announcementRepository.findById(a.getId()).orElseThrow();
        assertThat(reloaded.getShareViewCount()).isEqualTo(2L);
    }

    @Test
    void cancelledAnnouncement_rendersUnavailableStateInsteadOfTheTrip() throws Exception {
        AnnouncementEntity a = persistAnnouncement(AnnouncementStatus.CANCELLED);

        mockMvc.perform(get("/public/annonce/" + a.getId()).header("User-Agent", BROWSER_UA))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("n'est plus disponible")));
    }

    @Test
    void draftAnnouncement_isNotExposedPublicly() throws Exception {
        AnnouncementEntity a = persistAnnouncement(AnnouncementStatus.DRAFT);

        mockMvc.perform(get("/public/annonce/" + a.getId()).header("User-Agent", BROWSER_UA))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("n'est plus disponible")));
    }

    @Test
    void unknownAnnouncement_rendersUnavailableState() throws Exception {
        mockMvc.perform(get("/public/annonce/" + UUID.randomUUID()).header("User-Agent", BROWSER_UA))
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
        mockMvc.perform(get("/public/annonce/pas-un-uuid").header("User-Agent", BROWSER_UA))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("n'est plus disponible")));
    }

    @Test
    void unavailablePage_doesNotIncrementTheAttributionCounter() throws Exception {
        AnnouncementEntity a = persistAnnouncement(AnnouncementStatus.CANCELLED);

        mockMvc.perform(get("/public/annonce/" + a.getId()).header("User-Agent", BROWSER_UA)).andExpect(status().isOk());

        AnnouncementEntity reloaded = announcementRepository.findById(a.getId()).orElseThrow();
        assertThat(reloaded.getShareViewCount()).isZero();
    }

    /**
     * Un trajet dédié réserve sa capacité à une négociation privée. Le servir ici
     * exposerait publiquement le corridor, la date et le prix de cette
     * négociation, et enverrait des expéditeurs vers un appel à l'action que
     * {@code BidService} refuserait de toute façon.
     */
    @Test
    void dedicatedTripWithoutOpenSurplus_isNeverExposedPublicly() throws Exception {
        AnnouncementEntity a =
                persistAnnouncement(AnnouncementStatus.ACTIVE, UUID.randomUUID(), false);

        mockMvc.perform(get("/public/annonce/" + a.getId()).header("User-Agent", BROWSER_UA))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("n'est plus disponible")));
    }

    /** Surplus ouvert : la capacité excédentaire est bien offerte à des tiers. */
    @Test
    void dedicatedTripWithOpenSurplus_isExposedPublicly() throws Exception {
        AnnouncementEntity a =
                persistAnnouncement(AnnouncementStatus.ACTIVE, UUID.randomUUID(), true);

        mockMvc.perform(get("/public/annonce/" + a.getId()).header("User-Agent", BROWSER_UA))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Dakar")));
    }

    /**
     * FULL veut dire zéro kilo restant. La page annoncerait « place disponible :
     * 0 kg » à côté d'un bouton de réservation, alors que le feed de recherche
     * exclut déjà ce statut.
     */
    @Test
    void fullAnnouncement_isNotExposedPublicly() throws Exception {
        AnnouncementEntity a = persistAnnouncement(AnnouncementStatus.FULL);

        mockMvc.perform(get("/public/annonce/" + a.getId()).header("User-Agent", BROWSER_UA))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("n'est plus disponible")));
    }

    /**
     * Les balises Open Graph existent pour être moissonnées : chaque collage du
     * lien dans un post déclenche une visite de robot. Les compter mesurerait les
     * collages, pas les visiteurs.
     */
    @Test
    void previewCrawler_doesNotIncrementTheAttributionCounter() throws Exception {
        AnnouncementEntity a = persistAnnouncement(AnnouncementStatus.ACTIVE);

        mockMvc.perform(get("/public/annonce/" + a.getId())
                        .header("User-Agent", "facebookexternalhit/1.1"))
                .andExpect(status().isOk());

        AnnouncementEntity reloaded = announcementRepository.findById(a.getId()).orElseThrow();
        assertThat(reloaded.getShareViewCount()).isZero();
    }

    /**
     * L'URL canonique doit porter le context-path. Sans lui, le {@code og:url}
     * déclaré dans la carte de partage Facebook pointe sur un 404 : l'application
     * est servie sous {@code /api/v1}, comme le recollent déjà TrackingService et
     * RecipientController.
     */
    @Test
    void shareUrl_carriesTheApplicationContextPath() throws Exception {
        AnnouncementEntity a = persistAnnouncement(AnnouncementStatus.ACTIVE);

        mockMvc.perform(get("/public/annonce/" + a.getId()).header("User-Agent", BROWSER_UA))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/api/v1/public/annonce/" + a.getId())));
    }

    /**
     * La page s'adresse aux expéditeurs, comme l'affiche qui pointe vers elle.
     * Afficher le net voyageur annoncerait un tarif que personne ne paie, et
     * surtout un tarif différent de celui imprimé sur l'affiche.
     */
    @Test
    void publicPage_showsSenderPriceNotTravelerNet() throws Exception {
        AnnouncementEntity a = persistAnnouncement(AnnouncementStatus.ACTIVE);

        mockMvc.perform(get("/public/annonce/" + a.getId()).header("User-Agent", BROWSER_UA))
                .andExpect(status().isOk())
                // 8,00 est le net voyageur : la page doit afficher strictement plus.
                .andExpect(content().string(
                        org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.containsString(">8<"))));
    }
}
