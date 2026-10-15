package com.yadony.api.tracking;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "yadony.support.whatsapp-number=+33 6 00 00 00 00",
        "yadony.support.email=support@yadony.com",
        "app.store.ios=https://apps.apple.com/app/yadony/id0000000000"
})
class ParcelScanControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired TrackingEventRepository trackingEventRepository;
    @Autowired UserRepository userRepository;

    private BidEntity bid;

    @BeforeEach
    void seed() {
        trackingEventRepository.deleteAll();
        bidRepository.deleteAll();
        announcementRepository.deleteAll();

        UserEntity traveler = persistUser("Ibrahima", "Sowane", Role.TRAVELER);
        UserEntity sender = persistUser("Awa", "Diallo", Role.SENDER);

        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setTravelerId(traveler.getId());
        announcement.setDepartureCity("Paris");
        announcement.setArrivalCity("Dakar");
        announcement.setDepartureDate(LocalDate.now().plusDays(7));
        announcement.setTransportMode(TransportMode.PLANE);
        announcement.setPickupAddressLabel("Paris CDG");
        announcement.setPickupLat(new BigDecimal("48.860000"));
        announcement.setPickupLng(new BigDecimal("2.350000"));
        announcement.setDeliveryAddressLabel("Dakar Plateau");
        announcement.setDeliveryLat(new BigDecimal("14.690000"));
        announcement.setDeliveryLng(new BigDecimal("-17.440000"));
        announcement.setAvailableKg(new BigDecimal("10.00"));
        announcement.setTotalKg(new BigDecimal("10.00"));
        announcement.setPricePerKg(new BigDecimal("8.00"));
        announcement.setStatus(AnnouncementStatus.ACTIVE);
        announcement.setArrivalInstructions("12 rue Secrète, Dakar");
        announcement = announcementRepository.save(announcement);

        bid = new BidEntity();
        bid.setAnnouncementId(announcement.getId());
        bid.setSenderId(sender.getId());
        bid.setWeightKg(new BigDecimal("4.50"));
        bid.setStatus(BidStatus.IN_TRANSIT);
        bid.setTrackingToken(UUID.randomUUID().toString());
        bid.setTrackingNumber("YD7K4M2Q");
        bid.setDescription("2 boubous <script>alert(1)</script>");
        bid.setContentCategory("Vêtements & tissus,Chaussures");
        bid.setRecipientName("Moussa Keita");
        bid.setRecipientPhone("+221770000000");
        bid.setConfirmationCode("987654");
        bid = bidRepository.save(bid);
    }

    private UserEntity persistUser(String first, String last, Role role) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-scan-" + UUID.randomUUID());
        u.setFirstName(first);
        u.setLastName(last);
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        Set<Role> roles = new HashSet<>();
        roles.add(role);
        u.setRoles(roles);
        u.setTotalTrips(0);
        return userRepository.save(u);
    }

    @Test
    void colisEnCours_pagePubliqueSansJeton() throws Exception {
        mockMvc.perform(get("/tracking/{id}/scan", bid.getId()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("YD7K4M2Q")))
                .andExpect(content().string(containsString("Paris")))
                .andExpect(content().string(containsString("Dakar")))
                .andExpect(content().string(containsString("Vêtements &amp; tissus")))
                .andExpect(content().string(containsString("Chaussures")))
                .andExpect(content().string(containsString("4,5 kg")))
                .andExpect(content().string(containsString("Awa D.")))
                .andExpect(content().string(containsString("Moussa K.")))
                .andExpect(content().string(containsString("Ibrahima S.")))
                .andExpect(content().string(containsString("En attente du scan de départ")))
                .andExpect(content().string(containsString("yadony://colis/" + bid.getId())))
                .andExpect(content().string(containsString("https://wa.me/33600000000")))
                .andExpect(content().string(containsString("support@yadony.com")))
                .andExpect(content().string(containsString("App Store")))
                .andExpect(content().string(not(containsString("Google Play"))))
                .andExpect(content().string(containsString("noindex")));
    }

    @Test
    void etapeScannee_libelleEtLieu() throws Exception {
        TrackingEventEntity depart = new TrackingEventEntity();
        depart.setBidId(bid.getId());
        depart.setEventType(TrackingEventType.DEPART);
        depart.setScannedAt(LocalDateTime.of(2026, 10, 18, 9, 12));
        depart.setGpsLabel("Paris CDG");
        trackingEventRepository.save(depart);

        mockMvc.perform(get("/tracking/{id}/scan", bid.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Remis au voyageur, en route")))
                .andExpect(content().string(containsString("Colis remis au voyageur")))
                .andExpect(content().string(containsString("18/10/2026 09:12")))
                .andExpect(content().string(not(containsString("Paris CDG"))));
    }

    // Revue finale : le lieu de scan contient la rue (souvent l'adresse de l'expéditeur
    // pour la remise), il ne sort pas sur la page publique.
    @Test
    void lieuDeScan_jamaisAffiche() throws Exception {
        TrackingEventEntity depart = new TrackingEventEntity();
        depart.setBidId(bid.getId());
        depart.setEventType(TrackingEventType.DEPART);
        depart.setScannedAt(LocalDateTime.of(2026, 10, 18, 9, 12));
        depart.setGpsLabel("12 rue des Lilas, Saint-Denis, Île-de-France");
        trackingEventRepository.save(depart);

        mockMvc.perform(get("/tracking/{id}/scan", bid.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Colis remis au voyageur")))
                .andExpect(content().string(not(containsString("rue des Lilas"))))
                .andExpect(content().string(not(containsString("Saint-Denis"))));
    }

    @Test
    void numeroEtEmailDansLaDescription_masques() throws Exception {
        bid.setDescription("Pagne + savon, pour Moussa 77 123 45 67 ou +33 6 12 34 56 78, moussa@mail.com. Lot 2024");
        bidRepository.save(bid);
        mockMvc.perform(get("/tracking/{id}/scan", bid.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Pagne + savon")))
                .andExpect(content().string(not(containsString("123 45 67"))))
                .andExpect(content().string(not(containsString("12 34 56 78"))))
                .andExpect(content().string(not(containsString("moussa@mail.com"))))
                .andExpect(content().string(containsString("Lot 2024")));
    }

    @Test
    void urlDeLaPage_jamaisTransmiseEnReferer() throws Exception {
        mockMvc.perform(get("/tracking/{id}/scan", bid.getId()))
                .andExpect(content().string(containsString("<meta name=\"referrer\" content=\"no-referrer\">")));
    }

    @Test
    void colisLivreDepuisPlusDe14Jours_inactif() throws Exception {
        bid.setStatus(BidStatus.COMPLETED);
        bidRepository.save(bid);
        TrackingEventEntity arrivee = new TrackingEventEntity();
        arrivee.setBidId(bid.getId());
        arrivee.setEventType(TrackingEventType.ARRIVEE);
        arrivee.setScannedAt(LocalDateTime.now().minusDays(20));
        trackingEventRepository.save(arrivee);

        mockMvc.perform(get("/tracking/{id}/scan", bid.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("plus actif")))
                .andExpect(content().string(not(containsString("Awa D."))));
    }

    @Test
    void colisLivreRecemment_toujoursVisible() throws Exception {
        bid.setStatus(BidStatus.COMPLETED);
        bidRepository.save(bid);
        TrackingEventEntity arrivee = new TrackingEventEntity();
        arrivee.setBidId(bid.getId());
        arrivee.setEventType(TrackingEventType.ARRIVEE);
        arrivee.setScannedAt(LocalDateTime.now().minusDays(3));
        trackingEventRepository.save(arrivee);

        mockMvc.perform(get("/tracking/{id}/scan", bid.getId()))
                .andExpect(content().string(containsString("Livré")))
                .andExpect(content().string(containsString("Awa D.")));
    }

    @Test
    void jamaisDeDonneesSensibles() throws Exception {
        mockMvc.perform(get("/tracking/{id}/scan", bid.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Diallo"))))
                .andExpect(content().string(not(containsString("Keita"))))
                .andExpect(content().string(not(containsString("Sowane"))))
                .andExpect(content().string(not(containsString("770000000"))))
                .andExpect(content().string(not(containsString("987654"))))
                .andExpect(content().string(not(containsString("rue Secrète"))))
                .andExpect(content().string(not(containsString("—"))));
    }

    @Test
    void descriptionHtml_echappee() throws Exception {
        mockMvc.perform(get("/tracking/{id}/scan", bid.getId()))
                .andExpect(content().string(not(containsString("<script>alert(1)</script>"))))
                .andExpect(content().string(containsString("&lt;script&gt;")));
    }

    @Test
    void colisAnnule_pageInactiveSansDetail() throws Exception {
        bid.setStatus(BidStatus.CANCELLED);
        bidRepository.save(bid);
        mockMvc.perform(get("/tracking/{id}/scan", bid.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ce colis n'est plus actif")))
                .andExpect(content().string(not(containsString("Dakar"))))
                .andExpect(content().string(not(containsString("Awa D."))))
                .andExpect(content().string(not(containsString("yadony://colis"))))
                .andExpect(content().string(containsString("support@yadony.com")));
    }

    @Test
    void noShowRefuseNegociation_inactifs() throws Exception {
        for (BidStatus s : new BidStatus[]{BidStatus.NO_SHOW, BidStatus.PARCEL_REFUSED, BidStatus.NEGOTIATING}) {
            bid.setStatus(s);
            bid = bidRepository.save(bid);
            mockMvc.perform(get("/tracking/{id}/scan", bid.getId()))
                    .andExpect(content().string(containsString("plus actif")))
                    .andExpect(content().string(not(containsString("Dakar"))));
        }
    }

    @Test
    void colisLivre_afficheLivre() throws Exception {
        bid.setStatus(BidStatus.COMPLETED);
        bidRepository.save(bid);
        mockMvc.perform(get("/tracking/{id}/scan", bid.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Livré")));
    }

    @Test
    void uuidInconnu_404Html() throws Exception {
        mockMvc.perform(get("/tracking/{id}/scan", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("plus actif")));
    }

    @Test
    void identifiantNonUuid_404Html() throws Exception {
        mockMvc.perform(get("/tracking/{id}/scan", "pas-un-uuid"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("text/html"));
    }

    @Test
    void qrCode_resteProtege() throws Exception {
        mockMvc.perform(get("/tracking/{id}/qr-code", bid.getId()))
                .andExpect(status().isUnauthorized());
    }
}
