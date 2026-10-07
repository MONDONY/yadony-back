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
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FLUTTER-CB : {@code POST /tracking/{bidId}/confirm-delivery} refuse la livraison tant que
 * le trajet n'est pas parti (422 RFC 7807, code {@code trip-not-departed}), sans terminer le
 * colis ni publier {@link DeliveryConfirmedEvent} (qui libère le séquestre carte). Service
 * et base réels : le cas reproduit la recette (code saisi la veille du départ).
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@RecordApplicationEvents
class ConfirmDeliveryBeforeDepartureIntegrationTest {

    private static final ZoneId DAKAR = ZoneId.of("Africa/Dakar");

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired TrackingEventRepository trackingEventRepository;
    @Autowired ApplicationEvents events;

    private String travelerUid;
    private UUID travelerId;

    @BeforeEach
    void seedTraveler() {
        travelerUid = "uid-traveler-" + UUID.randomUUID();
        UserEntity u = new UserEntity();
        u.setFirebaseUid(travelerUid);
        u.setFirstName("Voyageur");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.VERIFIED);
        u.setRoles(new java.util.HashSet<>(Set.of(Role.TRAVELER)));
        u.setTotalTrips(0);
        travelerId = userRepository.save(u).getId();
    }

    private BidEntity seedHandedOverBid(ZonedDateTime departure) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(travelerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(departure.toLocalDate());
        a.setDepartureTime(departure.toLocalTime().withNano(0));
        a.setTimezone(departure.getZone().getId());
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Paris CDG");
        a.setPickupLat(new BigDecimal("48.860000"));
        a.setPickupLng(new BigDecimal("2.350000"));
        a.setDeliveryAddressLabel("Dakar Plateau");
        a.setDeliveryLat(new BigDecimal("14.690000"));
        a.setDeliveryLng(new BigDecimal("-17.440000"));
        a.setAvailableKg(new BigDecimal("10.00"));
        a.setTotalKg(new BigDecimal("10.00"));
        a.setPricePerKg(new BigDecimal("8.00"));
        a.setStatus(AnnouncementStatus.ACTIVE);
        a = announcementRepository.save(a);

        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(a.getId());
        bid.setSenderId(UUID.randomUUID());
        bid.setWeightKg(new BigDecimal("3.00"));
        bid.setStatus(BidStatus.HANDED_OVER);
        bid.setTrackingToken(UUID.randomUUID().toString());
        bid.setTrackingNumber("DNY" + String.format("%09d", Math.floorMod(System.nanoTime(), 1_000_000_000L)));
        bid.setConfirmationCode("123456");
        bid.setConfirmationCodeAttempts(0);
        return bidRepository.save(bid);
    }

    private UsernamePasswordAuthenticationToken traveler() {
        return new UsernamePasswordAuthenticationToken(travelerUid, null,
                List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    @Test
    void codeSaisiLaVeilleDuDepart_422TripNotDeparted_rienNeChange() throws Exception {
        // Recette : départ le lendemain 06:00, code saisi 23 h 30 avant.
        BidEntity bid = seedHandedOverBid(ZonedDateTime.now(DAKAR).plusMinutes(23 * 60 + 30));

        mockMvc.perform(post("/tracking/{bidId}/confirm-delivery", bid.getId())
                        .with(authentication(traveler()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmationCode\":\"123456\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("trip-not-departed"))
                .andExpect(jsonPath("$.type").value("https://yadony.app/errors/trip-not-departed"))
                .andExpect(jsonPath("$.title").value("Trip Not Departed"))
                .andExpect(jsonPath("$.detail").value(
                        "La livraison ne peut être confirmée qu'après le départ du trajet. Réessayez après le trajet."));

        BidEntity after = bidRepository.findById(bid.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(BidStatus.HANDED_OVER);
        assertThat(after.getConfirmationCode()).isEqualTo("123456");
        assertThat(after.getConfirmationCodeAttempts()).isZero();
        assertThat(trackingEventRepository.findByBidIdOrderByScannedAtAsc(bid.getId())).isEmpty();
        assertThat(events.stream(DeliveryConfirmedEvent.class)).isEmpty();
    }

    @Test
    void enAnglais_leMessageEstTraduit() throws Exception {
        BidEntity bid = seedHandedOverBid(ZonedDateTime.now(DAKAR).plusHours(2));

        mockMvc.perform(post("/tracking/{bidId}/confirm-delivery", bid.getId())
                        .with(authentication(traveler()))
                        .header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmationCode\":\"123456\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("trip-not-departed"))
                .andExpect(jsonPath("$.detail").value(
                        "Delivery can only be confirmed once the trip has departed. Please try again after the trip."));
    }

    @Test
    void apresLeDepart_livraisonConfirmee_etEvenementPublie() throws Exception {
        BidEntity bid = seedHandedOverBid(ZonedDateTime.now(DAKAR).minusHours(3));

        mockMvc.perform(post("/tracking/{bidId}/confirm-delivery", bid.getId())
                        .with(authentication(traveler()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmationCode\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventType").value("ARRIVEE"));

        assertThat(bidRepository.findById(bid.getId()).orElseThrow().getStatus())
                .isEqualTo(BidStatus.COMPLETED);
        assertThat(events.stream(DeliveryConfirmedEvent.class))
                .singleElement()
                .extracting(DeliveryConfirmedEvent::getBidId)
                .isEqualTo(bid.getId());
    }
}
