package com.yadony.api.matching.reception;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ReceptionControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired BidRecipientLinkRepository linkRepository;

    private UserEntity sender;
    private UserEntity traveler;
    private UserEntity recipient;
    private AnnouncementEntity announcement;

    @BeforeEach
    void seed() {
        sender = persistUser("Awa", Role.SENDER);
        traveler = persistUser("Moussa", Role.TRAVELER);
        recipient = persistUser("Fatou", Role.SENDER);
        announcement = persistAnnouncement(traveler.getId());
    }

    // ── GET /receptions ─────────────────────────────────────────────────────

    @Test
    void list_returnsPendingAndConfirmed_neverDeclined() throws Exception {
        BidEntity pending = persistBid(BidStatus.ACCEPTED);
        BidEntity confirmed = persistBid(BidStatus.HANDED_OVER);
        BidEntity declined = persistBid(BidStatus.ACCEPTED);
        link(pending, ReceptionLinkStatus.PENDING);
        link(confirmed, ReceptionLinkStatus.CONFIRMED);
        link(declined, ReceptionLinkStatus.DECLINED);

        mockMvc.perform(get("/receptions").with(authentication(as(recipient))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.bidId == '" + confirmed.getId() + "')].confirmationCode")
                        .value("123456"))
                .andExpect(jsonPath("$[?(@.bidId == '" + confirmed.getId() + "')].travelerFirstName")
                        .value("Moussa"))
                .andExpect(jsonPath("$[?(@.bidId == '" + pending.getId() + "')].senderFirstName")
                        .value("Awa"))
                .andExpect(jsonPath("$[?(@.bidId == '" + pending.getId() + "')].linkStatus")
                        .value("PENDING"));
    }

    @Test
    void list_withoutLinks_returnsEmptyArray() throws Exception {
        mockMvc.perform(get("/receptions").with(authentication(as(recipient))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ── GET /receptions/{bidId} ─────────────────────────────────────────────

    @Test
    void get_pending_hidesDetailsUntilConfirmed() throws Exception {
        BidEntity bid = persistBid(BidStatus.HANDED_OVER);
        link(bid, ReceptionLinkStatus.PENDING);

        mockMvc.perform(get("/receptions/{id}", bid.getId()).with(authentication(as(recipient))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bidId").value(bid.getId().toString()))
                .andExpect(jsonPath("$.linkStatus").value("PENDING"))
                .andExpect(jsonPath("$.bidStatus").value("HANDED_OVER"))
                .andExpect(jsonPath("$.departureCity").value("Paris"))
                .andExpect(jsonPath("$.departureDate").value(announcement.getDepartureDate().toString()))
                .andExpect(jsonPath("$.recipientName").value("Fatou Diop"))
                .andExpect(jsonPath("$.trackingNumber").doesNotExist())
                .andExpect(jsonPath("$.confirmationCode").doesNotExist())
                .andExpect(jsonPath("$.weightKg").doesNotExist())
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void get_declinedOrForeignLink_returns404ProblemDetail() throws Exception {
        BidEntity declined = persistBid(BidStatus.ACCEPTED);
        link(declined, ReceptionLinkStatus.DECLINED);

        mockMvc.perform(get("/receptions/{id}", declined.getId()).with(authentication(as(recipient))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value(endsWith("reception-not-found")));

        mockMvc.perform(get("/receptions/{id}", declined.getId()).with(authentication(as(traveler))))
                .andExpect(status().isNotFound());
    }

    // ── POST confirm / decline ──────────────────────────────────────────────

    @Test
    void confirm_isIdempotent_andRevealsCode() throws Exception {
        BidEntity bid = persistBid(BidStatus.IN_TRANSIT);
        link(bid, ReceptionLinkStatus.PENDING);

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/receptions/{id}/confirm", bid.getId()).with(authentication(as(recipient))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.linkStatus").value("CONFIRMED"))
                    .andExpect(jsonPath("$.confirmationCode").value("123456"))
                    .andExpect(jsonPath("$.trackingNumber").value(bid.getTrackingNumber()));
        }
        assertThat(linkRepository.findByBidId(bid.getId()).orElseThrow().getRespondedAt()).isNotNull();
    }

    @Test
    void decline_pending_returns204_thenHidden() throws Exception {
        BidEntity bid = persistBid(BidStatus.ACCEPTED);
        link(bid, ReceptionLinkStatus.PENDING);

        mockMvc.perform(post("/receptions/{id}/decline", bid.getId()).with(authentication(as(recipient))))
                .andExpect(status().isNoContent());

        assertThat(linkRepository.findByBidId(bid.getId()).orElseThrow().getStatus())
                .isEqualTo(ReceptionLinkStatus.DECLINED);
        mockMvc.perform(post("/receptions/{id}/confirm", bid.getId()).with(authentication(as(recipient))))
                .andExpect(status().isNotFound());
    }

    @Test
    void decline_confirmed_returns409ProblemDetail() throws Exception {
        BidEntity bid = persistBid(BidStatus.ACCEPTED);
        link(bid, ReceptionLinkStatus.CONFIRMED);

        mockMvc.perform(post("/receptions/{id}/decline", bid.getId()).with(authentication(as(recipient))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value(endsWith("reception-already-confirmed")));
    }

    @Test
    void decline_withoutLink_returns404() throws Exception {
        BidEntity bid = persistBid(BidStatus.ACCEPTED);

        mockMvc.perform(post("/receptions/{id}/decline", bid.getId()).with(authentication(as(recipient))))
                .andExpect(status().isNotFound());
    }

    // ── Timeline et BidResponse ─────────────────────────────────────────────

    @Test
    void trackingEvents_confirmedRecipientReads_pendingRecipientForbidden() throws Exception {
        BidEntity bid = persistBid(BidStatus.HANDED_OVER);
        BidRecipientLinkEntity link = link(bid, ReceptionLinkStatus.PENDING);

        mockMvc.perform(get("/tracking/{id}/events", bid.getId()).with(authentication(as(recipient))))
                .andExpect(status().isForbidden());

        link.respond(ReceptionLinkStatus.CONFIRMED, java.time.OffsetDateTime.now());
        linkRepository.save(link);

        mockMvc.perform(get("/tracking/{id}/events", bid.getId()).with(authentication(as(recipient))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void bidResponse_recipientAppStatus_senderSeesEveryAnswer() throws Exception {
        BidEntity pending = persistBid(BidStatus.ACCEPTED);
        BidEntity confirmed = persistBid(BidStatus.ACCEPTED);
        BidEntity declined = persistBid(BidStatus.ACCEPTED);
        BidEntity unlinked = persistBid(BidStatus.ACCEPTED);
        link(pending, ReceptionLinkStatus.PENDING);
        link(confirmed, ReceptionLinkStatus.CONFIRMED);
        link(declined, ReceptionLinkStatus.DECLINED);

        mockMvc.perform(get("/bids/{id}", pending.getId()).with(authentication(as(sender))))
                .andExpect(jsonPath("$.recipientAppStatus").value("PENDING"));
        mockMvc.perform(get("/bids/{id}", confirmed.getId()).with(authentication(as(sender))))
                .andExpect(jsonPath("$.recipientAppStatus").value("CONFIRMED"));
        mockMvc.perform(get("/bids/{id}", declined.getId()).with(authentication(as(sender))))
                .andExpect(jsonPath("$.recipientAppStatus").value("DECLINED"));
        mockMvc.perform(get("/bids/{id}", unlinked.getId()).with(authentication(as(sender))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientAppStatus").doesNotExist());
    }

    /** Lot 3B : le voyageur sait qui suit le colis dans l'app, jamais une attente ou un refus. */
    @Test
    void bidResponse_recipientAppStatus_travelerSeesConfirmedOnly() throws Exception {
        BidEntity pending = persistBid(BidStatus.ACCEPTED);
        BidEntity confirmed = persistBid(BidStatus.ACCEPTED);
        BidEntity declined = persistBid(BidStatus.ACCEPTED);
        BidEntity unlinked = persistBid(BidStatus.ACCEPTED);
        link(pending, ReceptionLinkStatus.PENDING);
        link(confirmed, ReceptionLinkStatus.CONFIRMED);
        link(declined, ReceptionLinkStatus.DECLINED);

        mockMvc.perform(get("/bids/{id}", confirmed.getId()).with(authentication(as(traveler))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientAppStatus").value("CONFIRMED"));
        for (BidEntity hidden : List.of(pending, declined, unlinked)) {
            mockMvc.perform(get("/bids/{id}", hidden.getId()).with(authentication(as(traveler))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.recipientAppStatus").doesNotExist());
        }

        mockMvc.perform(get("/announcements/{id}/bids", announcement.getId()).with(authentication(as(traveler))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + confirmed.getId() + "')].recipientAppStatus")
                        .value("CONFIRMED"))
                .andExpect(jsonPath("$[?(@.recipientAppStatus == 'PENDING')]").isEmpty())
                .andExpect(jsonPath("$[?(@.recipientAppStatus == 'DECLINED')]").isEmpty())
                .andExpect(jsonPath("$[?(@.recipientAppStatus == 'CONFIRMED')]", hasSize(1)));
    }

    // Sentry FLUTTER-6J : le destinataire inscrit qui masque son numéro n'est
    // joignable que par la messagerie de l'app, pas par téléphone.
    @Test
    void bidResponse_recipientHidesPhone_travelerGetsNoPhone_senderKeepsIt() throws Exception {
        recipient.setHidePhoneNumber(true);
        userRepository.save(recipient);
        BidEntity confirmed = persistBid(BidStatus.ACCEPTED);
        BidEntity pending = persistBid(BidStatus.ACCEPTED);
        link(confirmed, ReceptionLinkStatus.CONFIRMED);
        link(pending, ReceptionLinkStatus.PENDING);

        mockMvc.perform(get("/bids/{id}", confirmed.getId()).with(authentication(as(traveler))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientPhone").doesNotExist())
                .andExpect(jsonPath("$.recipientPhoneHidden").value(true));
        mockMvc.perform(get("/bids/{id}", confirmed.getId()).with(authentication(as(sender))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientPhone").value(confirmed.getRecipientPhone()))
                .andExpect(jsonPath("$.recipientPhoneHidden").value(false));
        // Pas encore confirmé : pas de messagerie destinataire, le téléphone reste.
        mockMvc.perform(get("/bids/{id}", pending.getId()).with(authentication(as(traveler))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientPhone").value(pending.getRecipientPhone()))
                .andExpect(jsonPath("$.recipientPhoneHidden").value(false));
    }

    @Test
    void bidResponse_recipientKeepsPhoneVisible_travelerGetsPhone() throws Exception {
        BidEntity confirmed = persistBid(BidStatus.ACCEPTED);
        link(confirmed, ReceptionLinkStatus.CONFIRMED);

        mockMvc.perform(get("/bids/{id}", confirmed.getId()).with(authentication(as(traveler))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientPhone").value(confirmed.getRecipientPhone()))
                .andExpect(jsonPath("$.recipientPhoneHidden").value(false));
    }

    // ── Requête de rattrapage (H2 mode PostgreSQL) ──────────────────────────

    @Test
    void findCatchUpCandidates_excludesLinkedOwnAndInactiveBids() {
        String phone = "+221 77 " + (100 + (int) (Math.random() * 899)) + " 45 67";
        BidEntity candidate = persistBid(BidStatus.ACCEPTED, phone);
        BidEntity alreadyLinked = persistBid(BidStatus.ACCEPTED, phone);
        link(alreadyLinked, ReceptionLinkStatus.PENDING);
        BidEntity completed = persistBid(BidStatus.COMPLETED, phone);
        BidEntity ownBid = persistBid(BidStatus.ACCEPTED, phone);
        ownBid.setSenderId(recipient.getId());
        bidRepository.save(ownBid);
        BidEntity otherSuffix = persistBid(BidStatus.ACCEPTED, "+221 77 000 00 01");

        List<BidEntity> candidates = linkRepository.findCatchUpCandidates(
                recipient.getId(), BidStatus.IN_FLIGHT, "%7");

        assertThat(candidates).extracting(BidEntity::getId)
                .contains(candidate.getId())
                .doesNotContain(alreadyLinked.getId(), completed.getId(), ownBid.getId(), otherSuffix.getId());

        List<BidEntity> asTraveler = linkRepository.findCatchUpCandidates(
                traveler.getId(), BidStatus.IN_FLIGHT, "%7");
        assertThat(asTraveler).extracting(BidEntity::getId).doesNotContain(candidate.getId());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static UsernamePasswordAuthenticationToken as(UserEntity user) {
        return new UsernamePasswordAuthenticationToken(user.getFirebaseUid(), null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER"), new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private UserEntity persistUser(String firstName, Role role) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-reception-" + UUID.randomUUID());
        u.setFirstName(firstName);
        u.setLastName("Test");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        Set<Role> roles = new HashSet<>();
        roles.add(role);
        u.setRoles(roles);
        u.setTotalTrips(0);
        return userRepository.save(u);
    }

    private AnnouncementEntity persistAnnouncement(UUID travelerId) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(travelerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
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
        a.setArrivalInstructions("Retrait au marché Sandaga");
        a.setStatus(AnnouncementStatus.ACTIVE);
        return announcementRepository.save(a);
    }

    private BidEntity persistBid(BidStatus status) {
        return persistBid(status, "+221 77 123 45 67");
    }

    private BidEntity persistBid(BidStatus status, String recipientPhone) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcement.getId());
        bid.setSenderId(sender.getId());
        bid.setWeightKg(new BigDecimal("3.00"));
        bid.setStatus(status);
        bid.setRecipientName("Fatou Diop");
        bid.setRecipientPhone(recipientPhone);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        bid.setTrackingNumber("DON-" + suffix);
        bid.setTrackingToken(UUID.randomUUID().toString());
        bid.setConfirmationCode("123456");
        return bidRepository.save(bid);
    }

    private BidRecipientLinkEntity link(BidEntity bid, ReceptionLinkStatus status) {
        BidRecipientLinkEntity link = new BidRecipientLinkEntity(bid.getId(), recipient.getId());
        if (status != ReceptionLinkStatus.PENDING) {
            link.respond(status, java.time.OffsetDateTime.now());
        }
        return linkRepository.save(link);
    }
}
