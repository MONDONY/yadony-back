package com.yadony.api.alerts;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.matching.events.TripCapacityFreedEvent;
import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@RecordApplicationEvents
@DisplayName("« Me prévenir si de la place se libère » — endpoints et événement FULL → ACTIVE (FLUTTER-JH)")
class TripSpaceAlertControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired UserRepository userRepository;
    @Autowired TripSpaceAlertRepository alertRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired ApplicationEvents events;

    private UserEntity traveler;
    private UUID fullTrip;
    private UUID activeTrip;

    @BeforeEach
    void seed() {
        alertRepository.deleteAll();
        announcementRepository.deleteAll();
        userRepository.deleteAll();
        traveler = user("tsa-traveler", Role.TRAVELER);
        user("tsa-sender", Role.SENDER);
        fullTrip = trip(AnnouncementStatus.FULL, LocalDate.now().plusDays(4), "0");
        activeTrip = trip(AnnouncementStatus.ACTIVE, LocalDate.now().plusDays(4), "6");
    }

    @Test
    @DisplayName("abonnement sur un trajet complet, idempotent, listé puis retiré")
    void subscribe_list_unsubscribe() throws Exception {
        mockMvc.perform(put("/me/trip-space-alerts/" + fullTrip).with(as("tsa-sender")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.announcementId").value(fullTrip.toString()));
        mockMvc.perform(put("/me/trip-space-alerts/" + fullTrip).with(as("tsa-sender")))
                .andExpect(status().isOk());
        assertThat(alertRepository.findAll()).hasSize(1);

        mockMvc.perform(get("/me/trip-space-alerts").with(as("tsa-sender")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].announcementId").value(fullTrip.toString()));

        mockMvc.perform(delete("/me/trip-space-alerts/" + fullTrip).with(as("tsa-sender")))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/me/trip-space-alerts").with(as("tsa-sender")))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("trajet réservable → 409 trip-not-full")
    void subscribe_activeTrip_conflict() throws Exception {
        mockMvc.perform(put("/me/trip-space-alerts/" + activeTrip).with(as("tsa-sender")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("trip-not-full"));
    }

    @Test
    @DisplayName("son propre trajet → 422 ; trajet parti → 409 ; trajet inconnu → 404")
    void subscribe_refusals() throws Exception {
        mockMvc.perform(put("/me/trip-space-alerts/" + fullTrip).with(as("tsa-traveler")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("trip-space-alert-own-trip"));
        UUID departed = trip(AnnouncementStatus.FULL, LocalDate.now().minusDays(1), "0");
        mockMvc.perform(put("/me/trip-space-alerts/" + departed).with(as("tsa-sender")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("trip-departure-passed"));
        mockMvc.perform(put("/me/trip-space-alerts/" + UUID.randomUUID()).with(as("tsa-sender")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("sans authentification → refusé")
    void subscribe_requiresAuthentication() throws Exception {
        mockMvc.perform(put("/me/trip-space-alerts/" + fullTrip))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
    }

    @Test
    @DisplayName("un colis annulé rouvre la place : FULL → ACTIVE publie TripCapacityFreedEvent, une fois")
    void releasingCapacity_publishesTripCapacityFreedEvent() {
        transactionTemplate.executeWithoutResult(tx -> {
            AnnouncementEntity trip = announcementRepository.findById(fullTrip).orElseThrow();
            trip.releaseCapacity(new BigDecimal("2"));
            announcementRepository.save(trip);
        });
        assertThat(events.stream(TripCapacityFreedEvent.class))
                .containsExactly(new TripCapacityFreedEvent(fullTrip, traveler.getId()));
        assertThat(announcementRepository.findById(fullTrip).orElseThrow().getStatus())
                .isEqualTo(AnnouncementStatus.ACTIVE);

        // Une nouvelle écriture sans changement de statut ne republie rien.
        transactionTemplate.executeWithoutResult(tx -> {
            AnnouncementEntity trip = announcementRepository.findById(fullTrip).orElseThrow();
            trip.releaseCapacity(new BigDecimal("1"));
            announcementRepository.save(trip);
        });
        assertThat(events.stream(TripCapacityFreedEvent.class)).hasSize(1);
    }

    @Test
    @DisplayName("un trajet qui se remplit (ACTIVE → FULL) ne publie rien")
    void fillingTrip_publishesNothing() {
        transactionTemplate.executeWithoutResult(tx -> {
            AnnouncementEntity trip = announcementRepository.findById(activeTrip).orElseThrow();
            trip.reserveCapacity(new BigDecimal("6"));
            announcementRepository.save(trip);
        });
        assertThat(announcementRepository.findById(activeTrip).orElseThrow().getStatus())
                .isEqualTo(AnnouncementStatus.FULL);
        assertThat(events.stream(TripCapacityFreedEvent.class)).isEmpty();
    }

    private static RequestPostProcessor as(String uid) {
        return authentication(new UsernamePasswordAuthenticationToken(uid, null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER"))));
    }

    private UserEntity user(String uid, Role role) {
        var user = new UserEntity();
        user.setFirebaseUid(uid);
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.VERIFIED);
        user.setRoles(new HashSet<>(List.of(role)));
        return userRepository.save(user);
    }

    private UUID trip(AnnouncementStatus status, LocalDate departure, String availableKg) {
        AnnouncementEntity e = new AnnouncementEntity();
        e.setTravelerId(traveler.getId());
        e.setDepartureCity("Paris");
        e.setArrivalCity("Dakar");
        e.setDepartureDate(departure);
        e.setAvailableKg(new BigDecimal(availableKg));
        e.setTotalKg(new BigDecimal("6"));
        e.setPricePerKg(new BigDecimal("10"));
        e.setCurrency("EUR");
        e.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.CASH));
        e.setStatus(status);
        e.setTransportMode(TransportMode.PLANE);
        e.setPickupAddressLabel("Test pickup");
        e.setPickupLat(BigDecimal.valueOf(48.8566));
        e.setPickupLng(BigDecimal.valueOf(2.3522));
        e.setDeliveryAddressLabel("Test delivery");
        e.setDeliveryLat(BigDecimal.valueOf(14.6928));
        e.setDeliveryLng(BigDecimal.valueOf(-17.4467));
        return announcementRepository.save(e).getId();
    }
}
