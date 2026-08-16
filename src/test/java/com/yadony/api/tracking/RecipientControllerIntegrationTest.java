package com.yadony.api.tracking;

import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import org.junit.jupiter.api.BeforeEach;
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Page publique de suivi destinataire ({@code GET /tracking/public/{token}}) :
 * accessible sans authentification (permitAll dans SecurityConfig), rendu
 * Thymeleaf léger, aucune donnée personnelle du destinataire.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class RecipientControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired TrackingEventRepository trackingEventRepository;

    private String trackingToken;
    private BidEntity bid;

    @BeforeEach
    void seed() {
        trackingEventRepository.deleteAll();
        bidRepository.deleteAll();
        announcementRepository.deleteAll();

        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setTravelerId(UUID.randomUUID());
        announcement.setDepartureCity("Paris");
        announcement.setArrivalCity("Abidjan");
        announcement.setDepartureDate(LocalDate.now().plusDays(7));
        announcement.setTransportMode(TransportMode.PLANE);
        announcement.setPickupAddressLabel("Paris CDG");
        announcement.setPickupLat(new BigDecimal("48.860000"));
        announcement.setPickupLng(new BigDecimal("2.350000"));
        announcement.setDeliveryAddressLabel("Abidjan Plateau");
        announcement.setDeliveryLat(new BigDecimal("5.320000"));
        announcement.setDeliveryLng(new BigDecimal("-4.020000"));
        announcement.setAvailableKg(new BigDecimal("10.00"));
        announcement.setTotalKg(new BigDecimal("10.00"));
        announcement.setPricePerKg(new BigDecimal("8.00"));
        announcement.setStatus(AnnouncementStatus.ACTIVE);
        announcement = announcementRepository.save(announcement);

        trackingToken = UUID.randomUUID().toString();
        bid = new BidEntity();
        bid.setAnnouncementId(announcement.getId());
        bid.setSenderId(UUID.randomUUID());
        bid.setWeightKg(new BigDecimal("3.00"));
        bid.setStatus(BidStatus.IN_TRANSIT);
        bid.setTrackingToken(trackingToken);
        bid.setTrackingNumber("DNY123456789");
        bid = bidRepository.save(bid);
    }

    private TrackingEventEntity event(TrackingEventType type, LocalDateTime at, String photoUrl) {
        TrackingEventEntity e = new TrackingEventEntity();
        e.setBidId(bid.getId());
        e.setEventType(type);
        e.setScannedAt(at);
        e.setPhotoUrl(photoUrl);
        return trackingEventRepository.save(e);
    }

    // ── Critère 1 : lien valide → page avec étapes et statut clair ────────────

    @Test
    void validToken_rendersTimelineWithStepsAndCorridor() throws Exception {
        event(TrackingEventType.DEPART, LocalDateTime.now().minusHours(6), null);
        event(TrackingEventType.TRANSIT, LocalDateTime.now().minusHours(2), null);

        mockMvc.perform(get("/tracking/public/{token}", trackingToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Paris")))
                .andExpect(content().string(containsString("Abidjan")))
                .andExpect(content().string(containsString("Colis remis au voyageur")))
                .andExpect(content().string(containsString("En transit")))
                .andExpect(content().string(containsString("DNY123456789")))
                .andExpect(content().string(not(containsString("invalide ou a expiré"))));
    }

    @Test
    void validToken_noEventsYet_showsWaitingStep() throws Exception {
        mockMvc.perform(get("/tracking/public/{token}", trackingToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("En attente du scan de départ")));
    }

    @Test
    void validToken_arriveeEvent_showsDeliveredStatus() throws Exception {
        event(TrackingEventType.DEPART, LocalDateTime.now().minusDays(1), null);
        event(TrackingEventType.ARRIVEE, LocalDateTime.now().minusHours(1), null);

        mockMvc.perform(get("/tracking/public/{token}", trackingToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Livraison confirmée")));
    }

    // ── Critère 2 : photos de scan en loading="lazy" (NFR15, 3G lente) ────────

    @Test
    void scanPhoto_isRenderedWithLazyLoading() throws Exception {
        event(TrackingEventType.DEPART, LocalDateTime.now().minusHours(3),
                "https://cdn.example/scan-depart.jpg");

        mockMvc.perform(get("/tracking/public/{token}", trackingToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("loading=\"lazy\"")))
                .andExpect(content().string(containsString("https://cdn.example/scan-depart.jpg")));
    }

    @Test
    void publicPage_rendersQrCodeAndProofPhotos() throws Exception {
        event(TrackingEventType.DEPART, LocalDateTime.now().minusHours(8),
                "https://cdn.example/remise.jpg");
        event(TrackingEventType.ARRIVEE, LocalDateTime.now().minusHours(1),
                "https://cdn.example/arrivee.jpg");

        mockMvc.perform(get("/tracking/public/{token}", trackingToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("QR code du colis")))
                .andExpect(content().string(containsString("Colis remis au voyageur")))
                .andExpect(content().string(containsString("Arrivée confirmée")))
                .andExpect(content().string(containsString("https://cdn.example/remise.jpg")))
                .andExpect(content().string(containsString("https://cdn.example/arrivee.jpg")))
                .andExpect(content().string(containsString("data:image/png;base64,")));
    }

    @Test
    void publicPage_hidesConfirmationCodeUnlessSenderPublishedIt() throws Exception {
        bid.setConfirmationCode("123456");
        bid.setConfirmationCodePublicEnabled(false);
        bid = bidRepository.save(bid);

        mockMvc.perform(get("/tracking/public/{token}", trackingToken))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(">123456<"))))
                .andExpect(content().string(not(containsString("is-visible\">\n      <span class=\"label\">Code de retrait"))));
    }

    @Test
    void publicPage_showsConfirmationCodeWhenSenderPublishedIt() throws Exception {
        bid.setConfirmationCode("123456");
        bid.setConfirmationCodePublicEnabled(true);
        bid.setConfirmationCodeExpiry(LocalDateTime.now().plusDays(1));
        bid = bidRepository.save(bid);

        mockMvc.perform(get("/tracking/public/{token}", trackingToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Code de retrait")))
                .andExpect(content().string(containsString("123456")));
    }

    // ── Critère 3 : token invalide → message français, jamais de 500 ──────────

    @Test
    void invalidToken_showsFrenchInvalidMessage_withoutServerError() throws Exception {
        mockMvc.perform(get("/tracking/public/{token}", "token-inconnu"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ce lien de suivi est invalide ou a expiré")));
    }

    // ── Accès public : aucune authentification requise ────────────────────────

    @Test
    void trackingPage_isPublic_noAuthenticationRequired() throws Exception {
        // Aucun .with(authentication(...)) : la requête est anonyme. Un filtre
        // exigeant un token Firebase renverrait 401/403 au lieu de 200.
        mockMvc.perform(get("/tracking/public/{token}", trackingToken))
                .andExpect(status().isOk());
    }

    // ── Endpoint JSON de rafraîchissement du statut ───────────────────────────

    @Test
    void statusEndpoint_returnsCurrentStepAndEvents() throws Exception {
        event(TrackingEventType.DEPART, LocalDateTime.now().minusHours(4), null);

        mockMvc.perform(get("/tracking/public/{token}/status", trackingToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStep").value("Colis remis au voyageur — en route"))
                .andExpect(jsonPath("$.events[0].label").value("Colis remis au voyageur"));
    }

    @Test
    void statusEndpoint_unknownToken_returns404() throws Exception {
        mockMvc.perform(get("/tracking/public/{token}/status", "token-inconnu"))
                .andExpect(status().isNotFound());
    }

    @Test
    void trackingStatus_exposesArrivalInstructions() throws Exception {
        AnnouncementEntity announcement = announcementRepository.findById(bid.getAnnouncementId()).orElseThrow();
        announcement.setArrivalInstructions("Métro Châtelet, sortie 3");
        announcementRepository.save(announcement);

        bid.setStatus(BidStatus.ARRIVED);
        bidRepository.save(bid);

        mockMvc.perform(get("/tracking/public/" + trackingToken + "/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.arrivalInstructions").value("Métro Châtelet, sortie 3"));
    }
}
