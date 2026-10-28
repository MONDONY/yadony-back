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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Suivi approximatif du voyageur sur la page publique du destinataire (dernière position, sans historique). */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class RecipientControllerTrackingTest {

    @Autowired MockMvc mockMvc;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired TrackingEventRepository trackingEventRepository;
    @Autowired TripPositionSampleRepository sampleRepository;

    private AnnouncementEntity trip;
    private BidEntity bid;
    private String token;

    @BeforeEach
    void seed() {
        sampleRepository.deleteAll();
        trackingEventRepository.deleteAll();
        bidRepository.deleteAll();
        announcementRepository.deleteAll();

        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(UUID.randomUUID());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Abidjan");
        a.setDepartureDate(LocalDate.now().minusDays(1));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Paris CDG");
        a.setPickupLat(new BigDecimal("48.860000"));
        a.setPickupLng(new BigDecimal("2.350000"));
        a.setDeliveryAddressLabel("Abidjan Plateau");
        a.setDeliveryLat(new BigDecimal("5.320000"));
        a.setDeliveryLng(new BigDecimal("-4.020000"));
        a.setAvailableKg(new BigDecimal("10.00"));
        a.setTotalKg(new BigDecimal("10.00"));
        a.setPricePerKg(new BigDecimal("8.00"));
        a.setStatus(AnnouncementStatus.ACTIVE);
        trip = announcementRepository.save(a);

        token = UUID.randomUUID().toString();
        bid = new BidEntity();
        bid.setAnnouncementId(trip.getId());
        bid.setSenderId(UUID.randomUUID());
        bid.setWeightKg(new BigDecimal("3.00"));
        bid.setStatus(BidStatus.IN_TRANSIT);
        bid.setTrackingToken(token);
        bid.setTrackingNumber("DNY123456789");
        bid = bidRepository.save(bid);
    }

    private void scanned(LocalDateTime at) {
        TrackingEventEntity e = new TrackingEventEntity();
        e.setBidId(bid.getId());
        e.setEventType(TrackingEventType.DEPART);
        e.setScannedAt(at);
        trackingEventRepository.save(e);
    }

    private void sample(OffsetDateTime at, String city, Integer pct) {
        sampleRepository.save(new TripPositionSampleEntity(trip.getId(), at, city, "FR", pct, SampleSource.OUVERTURE));
    }

    @Test
    void statusJsonCarriesLastPositionOnly() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        scanned(now.minusHours(3).toLocalDateTime());
        sample(now.minusHours(2), "Lyon", 10);
        sample(now.minusMinutes(20), "Marseille", 40);
        trip.setDepartedAt(now.minusHours(3));
        announcementRepository.save(trip);

        mockMvc.perform(get("/tracking/public/{t}/status", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.travelerTracking.status").value("EN_ROUTE"))
                .andExpect(jsonPath("$.travelerTracking.nearestCity").value("Marseille"))
                .andExpect(jsonPath("$.travelerTracking.progressPct").value(40))
                .andExpect(jsonPath("$.travelerTracking.history").isEmpty())
                .andExpect(content().string(not(containsString("Lyon"))));
    }

    @Test
    void statusJsonOmitsTrackingBeforeParcelIsCarried() throws Exception {
        bid.setStatus(BidStatus.ACCEPTED);
        bidRepository.save(bid);

        mockMvc.perform(get("/tracking/public/{t}/status", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.travelerTracking").doesNotExist());
    }

    @Test
    void pageShowsBadgeProgressCityAndAgeInFrench() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        scanned(now.minusHours(3).toLocalDateTime());
        sample(now.minusMinutes(20), "Marseille", 40);

        mockMvc.perform(get("/tracking/public/{t}", token).header("Accept-Language", "fr"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Le voyageur est en route")))
                .andExpect(content().string(containsString("Près de Marseille")))
                .andExpect(content().string(containsString("width:40%")))
                .andExpect(content().string(containsString("Mis à jour il y a")));
    }

    @Test
    void pageIsTranslatedInEnglish() throws Exception {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        scanned(now.minusHours(3).toLocalDateTime());
        sample(now.minusMinutes(20), "Marseille", 40);

        mockMvc.perform(get("/tracking/public/{t}", token).header("Accept-Language", "en"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("The traveler is on the way")))
                .andExpect(content().string(containsString("Near Marseille")))
                .andExpect(content().string(containsString("Approximate position, never the exact address.")));
    }

    @Test
    void notActivatedUsesNeutralWording() throws Exception {
        mockMvc.perform(get("/tracking/public/{t}", token).header("Accept-Language", "fr"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ne partage pas sa position pour le moment")))
                .andExpect(content().string(not(containsString("Près de"))));
    }

    @Test
    void blockIsHiddenBeforeParcelIsCarried() throws Exception {
        bid.setStatus(BidStatus.ACCEPTED);
        bidRepository.save(bid);

        mockMvc.perform(get("/tracking/public/{t}", token))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("traveler-position"))));
    }
}
