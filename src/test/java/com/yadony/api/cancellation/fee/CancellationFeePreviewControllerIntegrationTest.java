package com.yadony.api.cancellation.fee;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.cancellation.dto.CancellationFeePreviewResponse;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /cancellations/bids/{bidId}/fee-preview} de bout en bout (H2, vrais services, frais allumés par le profil
 * de test) : ce que l'expéditeur perdrait s'il annulait maintenant, jamais visible d'un autre que lui.
 */
@SpringBootTest
@ActiveProfiles({"test", "cancellation-fee"})
@AutoConfigureMockMvc
class CancellationFeePreviewControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired PaymentRepository paymentRepository;

    @Test
    void sender_twoDaysBeforeDeparture_twentyPercentRetained() throws Exception {
        UserEntity sender = persistUser(Role.SENDER);
        Instant departure = Instant.now().plus(Duration.ofHours(48)).truncatedTo(ChronoUnit.SECONDS);
        AnnouncementEntity trip = persistTrip(persistUser(Role.TRAVELER), departure);
        BidEntity bid = persistBid(trip, sender, PaymentMethod.STRIPE);
        persistCapturedPayment(bid, "100.00");
        // Départ tel que relu en base, celui que lit le service (H2 décale la colonne selon le fuseau de la JVM).
        Instant storedDeparture = announcementRepository.findById(trip.getId()).orElseThrow()
                .getDepartureAt().toInstant();

        String body = mockMvc.perform(get("/cancellations/bids/{bidId}/fee-preview", bid.getId())
                        .with(authentication(asRole(sender, "SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rate").value(0.20))
                .andExpect(jsonPath("$.fee").value(20.00))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.refundAmount").value(80.00))
                .andExpect(jsonPath("$.capped").value(false))
                .andReturn().getResponse().getContentAsString();

        CancellationFeePreviewResponse preview = objectMapper.readValue(body, CancellationFeePreviewResponse.class);
        assertThat(preview.nextTierAt()).isEqualTo(storedDeparture.minus(Duration.ofHours(24)));
        assertThat(preview.nextTierAt()).isBetween(departure.minus(Duration.ofHours(26)),
                departure.minus(Duration.ofHours(22)));
    }

    @Test
    void sender_bigPayment_cappedTrue() throws Exception {
        UserEntity sender = persistUser(Role.SENDER);
        AnnouncementEntity trip = persistTrip(persistUser(Role.TRAVELER),
                Instant.now().plus(Duration.ofHours(48)).truncatedTo(ChronoUnit.SECONDS));
        BidEntity bid = persistBid(trip, sender, PaymentMethod.STRIPE);
        persistCapturedPayment(bid, "200.00");

        mockMvc.perform(get("/cancellations/bids/{bidId}/fee-preview", bid.getId())
                        .with(authentication(asRole(sender, "SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rate").value(0.20))
                .andExpect(jsonPath("$.fee").value(30.00))
                .andExpect(jsonPath("$.refundAmount").value(170.00))
                .andExpect(jsonPath("$.capped").value(true));
    }

    @Test
    void traveler_ofTheBid_403ProblemDetail() throws Exception {
        UserEntity traveler = persistUser(Role.TRAVELER);
        BidEntity bid = persistBid(persistTrip(traveler, Instant.now().plus(Duration.ofHours(48))),
                persistUser(Role.SENDER), PaymentMethod.STRIPE);
        persistCapturedPayment(bid, "100.00");

        mockMvc.perform(get("/cancellations/bids/{bidId}/fee-preview", bid.getId())
                        .with(authentication(asRole(traveler, "TRAVELER"))))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("forbidden"));
    }

    @Test
    void cash_feeZero() throws Exception {
        UserEntity sender = persistUser(Role.SENDER);
        BidEntity bid = persistBid(persistTrip(persistUser(Role.TRAVELER), Instant.now().plus(Duration.ofHours(12))),
                sender, PaymentMethod.CASH);

        mockMvc.perform(get("/cancellations/bids/{bidId}/fee-preview", bid.getId())
                        .with(authentication(asRole(sender, "SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rate").value(0))
                .andExpect(jsonPath("$.fee").value(0))
                .andExpect(jsonPath("$.refundAmount").value(0))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.capped").value(false))
                .andExpect(jsonPath("$.nextTierAt").doesNotExist());
    }

    @Test
    void unknownBid_404ProblemDetail() throws Exception {
        UserEntity sender = persistUser(Role.SENDER);

        mockMvc.perform(get("/cancellations/bids/{bidId}/fee-preview", UUID.randomUUID())
                        .with(authentication(asRole(sender, "SENDER"))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("bid-not-found"));
    }

    @Test
    void anonymous_401() throws Exception {
        mockMvc.perform(get("/cancellations/bids/{bidId}/fee-preview", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    // --- helpers ---

    private static UsernamePasswordAuthenticationToken asRole(UserEntity user, String role) {
        return new UsernamePasswordAuthenticationToken(user.getFirebaseUid(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private UserEntity persistUser(Role role) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("fee-preview-it-" + UUID.randomUUID());
        user.setFirstName("Test");
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.VERIFIED);
        user.setRoles(Set.of(role));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user);
    }

    private AnnouncementEntity persistTrip(UserEntity traveler, Instant departure) {
        OffsetDateTime departureAt = departure.atOffset(ZoneOffset.UTC);
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(traveler.getId());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(departureAt.toLocalDate());
        a.setDepartureAt(departureAt);
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Gare du Nord, Paris");
        a.setPickupLat(new BigDecimal("48.880756"));
        a.setPickupLng(new BigDecimal("2.354987"));
        a.setDeliveryAddressLabel("Aéroport de Dakar");
        a.setDeliveryLat(new BigDecimal("14.670833"));
        a.setDeliveryLng(new BigDecimal("-17.073056"));
        a.setAvailableKg(new BigDecimal("15.00"));
        a.setTotalKg(new BigDecimal("20.00"));
        a.setPricePerKg(new BigDecimal("10.00"));
        a.setCurrency("EUR");
        a.setTimezone("Europe/Paris");
        a.setStatus(AnnouncementStatus.ACTIVE);
        return announcementRepository.saveAndFlush(a);
    }

    private BidEntity persistBid(AnnouncementEntity trip, UserEntity sender, PaymentMethod method) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(trip.getId());
        bid.setSenderId(sender.getId());
        bid.setWeightKg(new BigDecimal("5.00"));
        bid.setCurrency("EUR");
        bid.setPaymentMethod(method);
        bid.setStatus(BidStatus.ACCEPTED);
        bid.setQrToken(UUID.randomUUID().toString());
        bid.setTrackingToken(UUID.randomUUID().toString());
        bid.setTrackingNumber("DNY" + String.format("%09d", Math.floorMod(System.nanoTime(), 1_000_000_000L)));
        return bidRepository.saveAndFlush(bid);
    }

    private void persistCapturedPayment(BidEntity bid, String amount) {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(bid.getId());
        p.setStripePaymentIntentId("pi_fee_preview_" + UUID.randomUUID());
        p.setAmount(new BigDecimal(amount));
        p.setCommissionAmount(new BigDecimal("4.76"));
        p.setCurrency("EUR");
        p.setStatus(PaymentStatus.ESCROW);
        p.setCapturedAt(Instant.now());
        paymentRepository.saveAndFlush(p);
    }
}
