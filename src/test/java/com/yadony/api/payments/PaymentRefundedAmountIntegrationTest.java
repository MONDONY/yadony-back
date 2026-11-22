package com.yadony.api.payments;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /payments/bid/{bidId}} : {@code refundedAmount} additif (montant réellement remboursé, en unités de la
 * devise comme {@code amount}), absent tant que rien n'est remboursé, jamais visible d'un tiers.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PaymentRefundedAmountIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired PaymentRepository paymentRepository;

    @Test
    void beforeRefund_fieldAbsent_existingFieldsIntact() throws Exception {
        Fixture f = fixture(null);

        mockMvc.perform(get("/payments/bid/{id}", f.bid.getId()).with(authentication(as(f.sender, "SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundedAmount").doesNotExist())
                .andExpect(jsonPath("$.amount").value(100.00))
                .andExpect(jsonPath("$.commissionAmount").value(4.76))
                .andExpect(jsonPath("$.status").value("ESCROW"))
                .andExpect(jsonPath("$.currency").value("eur"))
                .andExpect(jsonPath("$.bidId").value(f.bid.getId().toString()));
    }

    @Test
    void afterPartialRefund_exactAmountForSenderAndTraveler() throws Exception {
        Fixture f = fixture(new BigDecimal("70.00"));

        mockMvc.perform(get("/payments/bid/{id}", f.bid.getId()).with(authentication(as(f.sender, "SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundedAmount").value(70.00))
                .andExpect(jsonPath("$.amount").value(100.00))
                .andExpect(jsonPath("$.commissionAmount").value(4.76))
                .andExpect(jsonPath("$.status").value("ESCROW"));
        mockMvc.perform(get("/payments/bid/{id}", f.bid.getId()).with(authentication(as(f.traveler, "TRAVELER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundedAmount").value(70.00));
    }

    @Test
    void stranger_forbidden() throws Exception {
        Fixture f = fixture(new BigDecimal("70.00"));
        UserEntity stranger = persistUser(Role.SENDER);

        mockMvc.perform(get("/payments/bid/{id}", f.bid.getId()).with(authentication(as(stranger, "SENDER"))))
                .andExpect(status().isForbidden());
    }

    private record Fixture(UserEntity sender, UserEntity traveler, BidEntity bid) {}

    private Fixture fixture(BigDecimal refunded) {
        UserEntity sender = persistUser(Role.SENDER);
        UserEntity traveler = persistUser(Role.TRAVELER);
        AnnouncementEntity trip = persistTrip(traveler);
        BidEntity bid = persistBid(trip, sender);
        PaymentEntity p = new PaymentEntity();
        p.setBidId(bid.getId());
        p.setStripePaymentIntentId("pi_refunded_it_" + UUID.randomUUID());
        p.setAmount(new BigDecimal("100.00"));
        p.setCommissionAmount(new BigDecimal("4.76"));
        p.setCurrency("EUR");
        p.setStatus(PaymentStatus.ESCROW);
        p.setCapturedAt(Instant.now());
        p.setRefundedAmount(refunded);
        paymentRepository.saveAndFlush(p);
        return new Fixture(sender, traveler, bid);
    }

    private static UsernamePasswordAuthenticationToken as(UserEntity user, String role) {
        return new UsernamePasswordAuthenticationToken(user.getFirebaseUid(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    private UserEntity persistUser(Role role) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("refunded-it-" + UUID.randomUUID());
        user.setFirstName("Test");
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.VERIFIED);
        user.setRoles(Set.of(role));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user);
    }

    private AnnouncementEntity persistTrip(UserEntity traveler) {
        OffsetDateTime departureAt = Instant.now().plusSeconds(172_800).atOffset(ZoneOffset.UTC);
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

    private BidEntity persistBid(AnnouncementEntity trip, UserEntity sender) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(trip.getId());
        bid.setSenderId(sender.getId());
        bid.setWeightKg(new BigDecimal("5.00"));
        bid.setCurrency("EUR");
        bid.setPaymentMethod(PaymentMethod.STRIPE);
        bid.setStatus(BidStatus.ACCEPTED);
        bid.setQrToken(UUID.randomUUID().toString());
        bid.setTrackingToken(UUID.randomUUID().toString());
        bid.setTrackingNumber("DNY" + String.format("%09d", Math.floorMod(System.nanoTime(), 1_000_000_000L)));
        return bidRepository.saveAndFlush(bid);
    }
}
