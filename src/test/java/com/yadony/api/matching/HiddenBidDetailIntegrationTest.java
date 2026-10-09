package com.yadony.api.matching;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FLUTTER-HS — une demande retirée de sa liste ({@code DELETE /bids/{id}/me}) ne revient
 * plus par {@code GET /bids/{id}} (notification rouverte) pour celui qui l'a retirée : même
 * 404 RFC 7807 qu'un colis inexistant. L'autre participant la voit toujours.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("GET /bids/{id} : demande retirée de sa liste par l'appelant")
class HiddenBidDetailIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;

    private UserEntity traveler;
    private UserEntity sender;
    private UserEntity stranger;
    private BidEntity bid;

    @BeforeEach
    void seed() {
        traveler = persistUser("Moussa");
        sender = persistUser("Awa");
        stranger = persistUser("Tiers");
        bid = persistBid(persistAnnouncement(traveler), sender, BidStatus.CANCELLED);
    }

    @Test
    void expediteurApresRetrait_404BidNotFound() throws Exception {
        mockMvc.perform(get("/bids/" + bid.getId()).with(authentication(as(sender))))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/bids/" + bid.getId() + "/me").with(authentication(as(sender))))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/bids/" + bid.getId()).with(authentication(as(sender))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Bid Not Found"));

        // L'autre participant n'est pas concerné par ce retrait.
        mockMvc.perform(get("/bids/" + bid.getId()).with(authentication(as(traveler))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(bid.getId().toString()));
    }

    @Test
    void voyageurApresRetrait_404_expediteurVoitToujours() throws Exception {
        bid.setDeletedByTraveler(true);
        bidRepository.saveAndFlush(bid);

        mockMvc.perform(get("/bids/" + bid.getId()).with(authentication(as(traveler))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Bid Not Found"));

        mockMvc.perform(get("/bids/" + bid.getId()).with(authentication(as(sender))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(bid.getId().toString()));
    }

    @Test
    void nonParticipant_inchange_403() throws Exception {
        bid.setDeletedBySender(true);
        bidRepository.saveAndFlush(bid);

        mockMvc.perform(get("/bids/" + bid.getId()).with(authentication(as(stranger))))
                .andExpect(status().isForbidden());
    }

    private static UsernamePasswordAuthenticationToken as(UserEntity user) {
        return new UsernamePasswordAuthenticationToken(user.getFirebaseUid(), null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER"), new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private AnnouncementEntity persistAnnouncement(UserEntity traveler) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(traveler.getId());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureCountryCode("FR");
        a.setArrivalCountryCode("SN");
        a.setDepartureDate(LocalDate.now().plusDays(7));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Paris CDG");
        a.setPickupLat(new BigDecimal("48.860000"));
        a.setPickupLng(new BigDecimal("2.350000"));
        a.setDeliveryAddressLabel("Dakar Centre");
        a.setDeliveryLat(new BigDecimal("14.693000"));
        a.setDeliveryLng(new BigDecimal("-17.447000"));
        a.setAvailableKg(new BigDecimal("10.00"));
        a.setTotalKg(new BigDecimal("10.00"));
        a.setPricePerKg(new BigDecimal("5.00"));
        a.setStatus(AnnouncementStatus.ACTIVE);
        return announcementRepository.save(a);
    }

    private BidEntity persistBid(AnnouncementEntity a, UserEntity owner, BidStatus status) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(a.getId());
        b.setSenderId(owner.getId());
        b.setWeightKg(new BigDecimal("2.00"));
        b.setStatus(status);
        b.setRecipientName("Fatou Diop");
        b.setRecipientPhone("+221 77 123 45 67");
        b.setTrackingNumber("DON-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());
        b.setTrackingToken(UUID.randomUUID().toString());
        b.setConfirmationCode("123456");
        return bidRepository.saveAndFlush(b);
    }

    private UserEntity persistUser(String firstName) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-hiddenbid-" + UUID.randomUUID());
        u.setFirstName(firstName);
        u.setLastName("Test");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.SENDER);
        roles.add(Role.TRAVELER);
        u.setRoles(roles);
        u.setTotalTrips(0);
        return userRepository.save(u);
    }
}
