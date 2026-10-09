package com.yadony.api.tracking;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.tracking.events.ConfirmationCodeRequestedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /tracking/{bidId}/request-code} de bout en bout (FLUTTER-G2) : propriété,
 * statut, code encore valide, limite d'une demande par quart d'heure, et le drapeau
 * {@code pickupCodeRenewalNeeded} de {@code GET /bids/{id}}.
 *
 * <p>{@code BidRepository.findByIdForUpdate} compile en {@code FOR NO KEY UPDATE}, refusé
 * par H2 : le dépôt est espionné et la lecture verrouillée délègue à {@code findById}
 * (cf. {@code RecipientReplacementIntegrationTest}).
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PickupCodeRequestIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @MockitoSpyBean BidRepository bidRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean NotificationDispatcher notificationDispatcher;

    private UserEntity sender;
    private UserEntity traveler;
    private UserEntity stranger;
    private AnnouncementEntity announcement;

    @BeforeEach
    void seed() {
        doAnswer(inv -> bidRepository.findById(inv.getArgument(0)))
                .when(bidRepository).findByIdForUpdate(any());
        when(notificationDispatcher.messagesFor(any())).thenReturn(TestMessages.fr());
        sender = persistUser("Awa", Role.SENDER);
        traveler = persistUser("Moussa", Role.TRAVELER);
        stranger = persistUser("Ibrahima", Role.TRAVELER);
        announcement = persistAnnouncement();
    }

    @Test
    void blockedCode_travelerRequests_senderIsNotified_andAudited() throws Exception {
        BidEntity bid = persistBid(BidStatus.ARRIVED, null, null);

        request(bid, traveler)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedAt").value(notNullValue()))
                .andExpect(jsonPath("$.nextRequestAllowedAt").value(notNullValue()));

        assertThat(auditCount(bid)).isEqualTo(1);
        // Le dispatcher est un mock : on vérifie que l'événement lui parvient après commit
        // (texte et données du push : NotificationDispatcherTest).
        verify(notificationDispatcher, timeout(5000)).onConfirmationCodeRequested(
                new ConfirmationCodeRequestedEvent(bid.getId(), sender.getId()));
        // Rien ne change sur le colis : seul l'expéditeur génère le code.
        BidEntity saved = bidRepository.findById(bid.getId()).orElseThrow();
        assertThat(saved.getConfirmationCode()).isNull();
        assertThat(saved.getStatus()).isEqualTo(BidStatus.ARRIVED);
    }

    @Test
    void expiredCode_isAccepted() throws Exception {
        BidEntity bid = persistBid(BidStatus.IN_TRANSIT, "123456",
                LocalDateTime.now(ZoneOffset.UTC).minusHours(1));

        request(bid, traveler).andExpect(status().isOk());

        assertThat(auditCount(bid)).isEqualTo(1);
    }

    @Test
    void secondRequestWithin15min_is429_withNextRequestAllowedAt() throws Exception {
        BidEntity bid = persistBid(BidStatus.HANDED_OVER, null, null);
        request(bid, traveler).andExpect(status().isOk());

        request(bid, traveler)
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value(endsWith("code-request-too-soon")))
                .andExpect(jsonPath("$.code").value("code-request-too-soon"))
                .andExpect(jsonPath("$.detail").value("Demande déjà envoyée à l'expéditeur. Réessayez dans 15 min."))
                .andExpect(jsonPath("$.nextRequestAllowedAt").value(notNullValue()))
                .andExpect(jsonPath("$.retryAfterSeconds").value(notNullValue()));
        assertThat(auditCount(bid)).isEqualTo(1);
    }

    @Test
    void previousRequestOlderThan15min_isAccepted() throws Exception {
        BidEntity bid = persistBid(BidStatus.IN_TRANSIT, null, null);
        insertPastRequest(bid, Instant.now().minus(Duration.ofMinutes(16)));

        request(bid, traveler).andExpect(status().isOk());

        assertThat(auditCount(bid)).isEqualTo(2);
    }

    @Test
    void senderOrStranger_403() throws Exception {
        BidEntity bid = persistBid(BidStatus.IN_TRANSIT, null, null);

        request(bid, sender)
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        request(bid, stranger)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
        assertThat(auditCount(bid)).isZero();
        verify(notificationDispatcher, never()).onConfirmationCodeRequested(any());
    }

    @Test
    void parcelNotWithTraveler_409() throws Exception {
        for (BidStatus status : new BidStatus[]{BidStatus.ACCEPTED, BidStatus.COMPLETED}) {
            BidEntity bid = persistBid(status, null, null);
            request(bid, traveler)
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.code").value("code-request-not-allowed"));
            assertThat(auditCount(bid)).as(status.name()).isZero();
        }
    }

    @Test
    void validCode_409_codeStillValid() throws Exception {
        BidEntity bid = persistBid(BidStatus.IN_TRANSIT, "123456",
                LocalDateTime.now(ZoneOffset.UTC).plusDays(2));

        request(bid, traveler)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("code-still-valid"));
        assertThat(auditCount(bid)).isZero();
    }

    @Test
    void englishRequest_detailIsTranslated() throws Exception {
        BidEntity bid = persistBid(BidStatus.IN_TRANSIT, "123456", null);

        mockMvc.perform(post("/tracking/{id}/request-code", bid.getId())
                        .header("Accept-Language", "en")
                        .with(authentication(as(traveler))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        "The current pickup code is still valid. Ask the sender or the recipient for it."));
    }

    @Test
    void unknownBid_404() throws Exception {
        mockMvc.perform(post("/tracking/{id}/request-code", UUID.randomUUID())
                        .with(authentication(as(traveler))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("bid-not-found"));
    }

    @Test
    void withoutTravelerRole_isRejected() throws Exception {
        BidEntity bid = persistBid(BidStatus.IN_TRANSIT, null, null);
        mockMvc.perform(post("/tracking/{id}/request-code", bid.getId())
                        .with(authentication(new UsernamePasswordAuthenticationToken(traveler.getFirebaseUid(),
                                null, List.of(new SimpleGrantedAuthority("ROLE_SENDER"))))))
                .andExpect(status().isForbidden());
        assertThat(auditCount(bid)).isZero();
    }

    @Test
    void bidDetail_exposesRenewalFlagToBothParties() throws Exception {
        BidEntity blocked = persistBid(BidStatus.IN_TRANSIT, null, null);
        BidEntity valid = persistBid(BidStatus.IN_TRANSIT, "123456", null);

        for (UserEntity caller : new UserEntity[]{sender, traveler}) {
            mockMvc.perform(get("/bids/{id}", blocked.getId()).with(authentication(as(caller))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pickupCodeRenewalNeeded").value(true));
            mockMvc.perform(get("/bids/{id}", valid.getId()).with(authentication(as(caller))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.pickupCodeRenewalNeeded").value(false));
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Même conversion que {@code RecipientReplacementIntegrationTest#insertPastRequest}. */
    private void insertPastRequest(BidEntity bid, Instant at) {
        jdbcTemplate.update("INSERT INTO audit_log (entity_type, entity_id, action, actor_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                PickupCodeRequestService.AUDIT_ENTITY, bid.getId(), PickupCodeRequestService.AUDIT_ACTION,
                traveler.getId(), LocalDateTime.ofInstant(
                        LocalDateTime.ofInstant(at, ZoneOffset.UTC).atZone(ZoneId.systemDefault()).toInstant(),
                        ZoneOffset.UTC));
    }

    private int auditCount(BidEntity bid) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_type = ? AND action = ? AND entity_id = ?",
                Integer.class, PickupCodeRequestService.AUDIT_ENTITY, PickupCodeRequestService.AUDIT_ACTION,
                bid.getId());
    }

    private ResultActions request(BidEntity bid, UserEntity caller) throws Exception {
        return mockMvc.perform(post("/tracking/{id}/request-code", bid.getId())
                .with(authentication(as(caller))));
    }

    private static UsernamePasswordAuthenticationToken as(UserEntity user) {
        return new UsernamePasswordAuthenticationToken(user.getFirebaseUid(), null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER"), new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private UserEntity persistUser(String firstName, Role role) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-pickup-code-request-" + UUID.randomUUID());
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

    private AnnouncementEntity persistAnnouncement() {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(traveler.getId());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().minusDays(1));
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

    private BidEntity persistBid(BidStatus status, String code, LocalDateTime expiry) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(announcement.getId());
        bid.setSenderId(sender.getId());
        bid.setWeightKg(new BigDecimal("3.00"));
        bid.setStatus(status);
        bid.setRecipientName("Fatou Diop");
        bid.setRecipientPhone("+221 77 123 45 67");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        bid.setTrackingNumber("DON-" + suffix);
        bid.setTrackingToken(UUID.randomUUID().toString());
        bid.setConfirmationCode(code);
        bid.setConfirmationCodeExpiry(expiry);
        return bidRepository.save(bid);
    }
}
