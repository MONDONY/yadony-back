package com.yadony.api.matching.reception;

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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
 * {@code POST /bids/{bidId}/recipient/replacement-request} de bout en bout, et le masquage
 * du destinataire qui a refusé dans {@code GET /bids/{bidId}}.
 *
 * <p>{@code BidRepository.findByIdForUpdate} compile en {@code FOR NO KEY UPDATE}, refusé
 * par H2 : le dépôt est espionné et la lecture verrouillée délègue à {@code findById}
 * (cf. {@code RecipientChangeIntegrationTest}).
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class RecipientReplacementIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @MockitoSpyBean BidRepository bidRepository;
    @Autowired BidRecipientLinkRepository linkRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean NotificationDispatcher notificationDispatcher;

    private UserEntity sender;
    private UserEntity traveler;
    private UserEntity recipient;
    private AnnouncementEntity announcement;

    @BeforeEach
    void seed() {
        doAnswer(inv -> bidRepository.findById(inv.getArgument(0)))
                .when(bidRepository).findByIdForUpdate(any());
        when(notificationDispatcher.messagesFor(any())).thenReturn(TestMessages.fr());
        sender = persistUser("Awa", Role.SENDER);
        traveler = persistUser("Moussa", Role.TRAVELER);
        recipient = persistUser("Fatou", Role.SENDER);
        announcement = persistAnnouncement();
    }

    @Test
    void traveler_requestsAReplacement_senderIsNotified_andTheBidIsMasked() throws Exception {
        BidEntity bid = persistBid(BidStatus.IN_TRANSIT);
        declinedLink(bid, OffsetDateTime.now().minusHours(1));

        request(bid, traveler)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(bid.getId().toString()))
                .andExpect(jsonPath("$.recipientDeclined").value(true))
                .andExpect(jsonPath("$.recipientName").doesNotExist())
                .andExpect(jsonPath("$.recipientPhone").doesNotExist())
                .andExpect(jsonPath("$.recipientAppStatus").doesNotExist())
                .andExpect(jsonPath("$.recipientReplacementRequestedAt").value(notNullValue()));

        assertThat(auditCount(bid)).isEqualTo(1);
        verify(notificationDispatcher, timeout(5000)).notifyUser(eq(sender.getId()), eq("Destinataire à remplacer"),
                eq("Destinataire refusé. Le voyageur vous demande d'en désigner un autre."),
                eq(Map.of("type", "RECIPIENT_REPLACEMENT_REQUESTED", "bidId", bid.getId().toString())));
        // Rien ne change sur le colis : le code de retrait reste valable.
        BidEntity saved = bidRepository.findById(bid.getId()).orElseThrow();
        assertThat(saved.getConfirmationCode()).isEqualTo("123456");
        assertThat(saved.getStatus()).isEqualTo(BidStatus.IN_TRANSIT);

        // L'expéditeur voit toujours tout, et la date de la demande.
        mockMvc.perform(get("/bids/{id}", bid.getId()).with(authentication(as(sender))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientName").value("Fatou Diop"))
                .andExpect(jsonPath("$.recipientPhone").value("+221 77 123 45 67"))
                .andExpect(jsonPath("$.recipientAppStatus").value("DECLINED"))
                .andExpect(jsonPath("$.recipientDeclined").value(false))
                .andExpect(jsonPath("$.recipientReplacementRequestedAt").value(notNullValue()));
        // Le voyageur, sur le détail : nom et numéro masqués.
        mockMvc.perform(get("/bids/{id}", bid.getId()).with(authentication(as(traveler))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientName").doesNotExist())
                .andExpect(jsonPath("$.recipientPhone").doesNotExist())
                .andExpect(jsonPath("$.recipientDeclined").value(true));
    }

    @Test
    void secondRequestWithin12h_is429() throws Exception {
        BidEntity bid = persistBid(BidStatus.ARRIVED);
        declinedLink(bid, OffsetDateTime.now().minusHours(1));
        request(bid, traveler).andExpect(status().isOk());

        request(bid, traveler)
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value(endsWith("recipient-replacement-too-soon")))
                .andExpect(jsonPath("$.code").value("recipient-replacement-too-soon"))
                .andExpect(jsonPath("$.nextRequestAllowedAt").value(notNullValue()));
        assertThat(auditCount(bid)).isEqualTo(1);
    }

    @Test
    void previousRequestOlderThan12h_isAccepted() throws Exception {
        BidEntity bid = persistBid(BidStatus.IN_TRANSIT);
        declinedLink(bid, OffsetDateTime.now().minusDays(2));
        insertPastRequest(bid, Instant.now().minus(Duration.ofHours(13)));

        request(bid, traveler).andExpect(status().isOk());

        assertThat(auditCount(bid)).isEqualTo(2);
    }

    @Test
    void requestBeforeTheCurrentDecline_doesNotCount() throws Exception {
        BidEntity bid = persistBid(BidStatus.IN_TRANSIT);
        // Demande faite pour un refus précédent, puis un nouveau refus il y a 1 h.
        insertPastRequest(bid, Instant.now().minus(Duration.ofHours(3)));
        declinedLink(bid, OffsetDateTime.now().minusHours(1));

        request(bid, traveler).andExpect(status().isOk());
    }

    @Test
    void senderOrStranger_403() throws Exception {
        BidEntity bid = persistBid(BidStatus.IN_TRANSIT);
        declinedLink(bid, OffsetDateTime.now().minusHours(1));

        request(bid, sender)
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        request(bid, recipient).andExpect(status().isForbidden());
        assertThat(auditCount(bid)).isZero();
        verify(notificationDispatcher, never()).notifyUser(any(), anyString(), anyString(),
                eq(Map.of("type", "RECIPIENT_REPLACEMENT_REQUESTED", "bidId", bid.getId().toString())));
    }

    @Test
    void linkNotDeclined_409() throws Exception {
        BidEntity bid = persistBid(BidStatus.IN_TRANSIT);
        BidRecipientLinkEntity link = new BidRecipientLinkEntity(bid.getId(), recipient.getId());
        link.respond(ReceptionLinkStatus.CONFIRMED, OffsetDateTime.now());
        linkRepository.save(link);

        request(bid, traveler)
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value(endsWith("recipient-not-declined")));
    }

    @Test
    void noLink_409() throws Exception {
        BidEntity bid = persistBid(BidStatus.IN_TRANSIT);
        request(bid, traveler)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(endsWith("recipient-not-declined")));
    }

    @Test
    void deliveredParcel_409() throws Exception {
        BidEntity bid = persistBid(BidStatus.COMPLETED);
        declinedLink(bid, OffsetDateTime.now().minusHours(1));

        request(bid, traveler)
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value(endsWith("recipient-replacement-not-allowed")));
    }

    @Test
    void unknownBid_404() throws Exception {
        mockMvc.perform(post("/bids/{id}/recipient/replacement-request", UUID.randomUUID())
                        .with(authentication(as(traveler))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(endsWith("bid-not-found")));
    }

    @Test
    void unauthenticated_isRejected() throws Exception {
        BidEntity bid = persistBid(BidStatus.IN_TRANSIT);
        int status = mockMvc.perform(post("/bids/{id}/recipient/replacement-request", bid.getId()))
                .andReturn().getResponse().getStatus();
        assertThat(status).isIn(401, 403);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void declinedLink(BidEntity bid, OffsetDateTime at) {
        BidRecipientLinkEntity link = new BidRecipientLinkEntity(bid.getId(), recipient.getId());
        link.respond(ReceptionLinkStatus.DECLINED, at);
        linkRepository.save(link);
    }

    /**
     * Écrit la trace comme Hibernate l'écrirait pour {@code AuditLogEntity.createdAt}
     * ({@code LocalDateTime.now(UTC)}) : avec {@code hibernate.jdbc.time_zone: UTC}, ce
     * LocalDateTime est lu dans le fuseau de la JVM puis stocké en heure murale UTC.
     * Sans cette conversion, le test dépendrait du fuseau de la machine.
     */
    private void insertPastRequest(BidEntity bid, Instant at) {
        jdbcTemplate.update("INSERT INTO audit_log (entity_type, entity_id, action, actor_id, created_at) "
                        + "VALUES ('BID', ?, 'RECIPIENT_REPLACEMENT_REQUESTED', ?, ?)",
                bid.getId(), traveler.getId(), LocalDateTime.ofInstant(
                        LocalDateTime.ofInstant(at, ZoneOffset.UTC).atZone(ZoneId.systemDefault()).toInstant(),
                        ZoneOffset.UTC));
    }

    private int auditCount(BidEntity bid) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'BID' AND action = 'RECIPIENT_REPLACEMENT_REQUESTED' "
                        + "AND entity_id = ? AND actor_id = ?",
                Integer.class, bid.getId(), traveler.getId());
    }

    private ResultActions request(BidEntity bid, UserEntity caller) throws Exception {
        return mockMvc.perform(post("/bids/{id}/recipient/replacement-request", bid.getId())
                .with(authentication(as(caller))));
    }

    private static UsernamePasswordAuthenticationToken as(UserEntity user) {
        return new UsernamePasswordAuthenticationToken(user.getFirebaseUid(), null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER"), new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private UserEntity persistUser(String firstName, Role role) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-recipient-replacement-" + UUID.randomUUID());
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

    private BidEntity persistBid(BidStatus status) {
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
        bid.setConfirmationCode("123456");
        return bidRepository.save(bid);
    }
}
