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
import com.yadony.api.matching.RevokedTrackingTokenRepository;
import com.yadony.api.matching.TransportMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code PUT /bids/{bidId}/recipient} de bout en bout, et l'ancien lien de suivi public.
 *
 * <p>{@code BidRepository.findByIdForUpdate} compile en {@code FOR NO KEY UPDATE} sous le
 * dialecte PostgreSQL imposé au profil test, clause refusée par H2 (cf.
 * {@code AnnouncementArrivalControllerTest}) : le dépôt est espionné et la lecture
 * verrouillée délègue à {@code findById}. Le reste du flux est réel.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class RecipientChangeIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @MockitoSpyBean BidRepository bidRepository;
    @Autowired BidRecipientLinkRepository linkRepository;
    @Autowired RevokedTrackingTokenRepository revokedTokenRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private UserEntity sender;
    private UserEntity traveler;
    private UserEntity oldRecipient;
    private AnnouncementEntity announcement;

    @BeforeEach
    void seed() {
        doAnswer(inv -> bidRepository.findById(inv.getArgument(0)))
                .when(bidRepository).findByIdForUpdate(any());
        sender = persistUser("Awa", Role.SENDER);
        traveler = persistUser("Moussa", Role.TRAVELER);
        oldRecipient = persistUser("Fatou", Role.SENDER);
        announcement = persistAnnouncement();
    }

    // ── Changement de numéro ────────────────────────────────────────────────

    @Test
    void newNumber_rotatesTokenAndCode_andTheOldLinkSaysTheRecipientChanged() throws Exception {
        BidEntity bid = persistBid(BidStatus.IN_TRANSIT);
        String oldToken = bid.getTrackingToken();
        BidRecipientLinkEntity oldLink = linkRepository.save(new BidRecipientLinkEntity(bid.getId(), oldRecipient.getId()));

        change(bid, sender, "{\"recipientName\":\"Awa Sow\",\"recipientPhone\":\"+221781112233\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(bid.getId().toString()))
                .andExpect(jsonPath("$.recipientName").value("Awa Sow"))
                .andExpect(jsonPath("$.trackingToken").value(not(oldToken)))
                .andExpect(jsonPath("$.confirmationCode").value(not("123456")))
                .andExpect(jsonPath("$.recipientAppStatus").doesNotExist());

        BidEntity saved = bidRepository.findById(bid.getId()).orElseThrow();
        assertThat(saved.getRecipientPhone()).isEqualTo("+221781112233");
        assertThat(saved.getTrackingToken()).isNotEqualTo(oldToken);
        assertThat(saved.getConfirmationCode()).matches("\\d{6}").isNotEqualTo("123456");
        assertThat(saved.isConfirmationCodePublicEnabled()).isFalse();
        assertThat(saved.getConfirmationCodeRefreshCount()).isZero();
        assertThat(revokedTokenRepository.existsByToken(oldToken)).isTrue();

        // Ancien lien soft-deleted, jamais supprimé.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM bid_recipient_links WHERE id = ?", Boolean.class, oldLink.getId()))
                .isTrue();
        assertThat(linkRepository.findByBidId(bid.getId())).isEmpty();
        // L'ancien destinataire n'y a plus accès.
        mockMvc.perform(get("/receptions/{id}", bid.getId()).with(authentication(as(oldRecipient))))
                .andExpect(status().isNotFound());

        // Audit sans numéro en clair (lu en SQL : le jsonb ne se relit pas en Map sous H2).
        List<String> audit = auditPayloads(bid, "RECIPIENT_CHANGED");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0)).contains("+221 •••• 33").contains("+221 •••• 67")
                .doesNotContain("781112233").doesNotContain("123 45 67").doesNotContain("Awa Sow");

        // Ancien lien public : page explicite, statut 410.
        mockMvc.perform(get("/tracking/public/{token}", oldToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Le destinataire de ce colis a changé.")))
                .andExpect(content().string(containsString("Ce lien de suivi n'est plus valide.")))
                .andExpect(content().string(not(containsString(saved.getTrackingNumber()))));
        mockMvc.perform(get("/tracking/public/{token}/status", oldToken))
                .andExpect(status().isGone())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value(endsWith("tracking-link-revoked")))
                .andExpect(jsonPath("$.status").value(410));

        // Nouveau lien public : il fonctionne.
        mockMvc.perform(get("/tracking/public/{token}/status", saved.getTrackingToken()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/tracking/public/{token}", saved.getTrackingToken()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Le destinataire de ce colis a changé."))));
    }

    @Test
    void unknownToken_keepsTheGenericInvalidPage() throws Exception {
        String unknown = UUID.randomUUID().toString();
        mockMvc.perform(get("/tracking/public/{token}", unknown))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Lien invalide ou expiré")))
                .andExpect(content().string(not(containsString("Le destinataire de ce colis a changé."))));
        mockMvc.perform(get("/tracking/public/{token}/status", unknown))
                .andExpect(status().isNotFound());
    }

    @Test
    void revokedToken_cannotRateTheDelivery() throws Exception {
        BidEntity bid = persistBid(BidStatus.ARRIVED);
        String oldToken = bid.getTrackingToken();
        change(bid, sender, "{\"recipientName\":\"Awa\",\"recipientPhone\":\"+221781112233\"}")
                .andExpect(status().isOk());

        mockMvc.perform(post("/ratings/recipient")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trackingToken\":\"" + oldToken + "\",\"stars\":5}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void sameNumber_updatesTheNameOnly() throws Exception {
        BidEntity bid = persistBid(BidStatus.HANDED_OVER);
        String oldToken = bid.getTrackingToken();

        change(bid, sender, "{\"recipientName\":\"Fatou Ndiaye\",\"recipientPhone\":\"+221771234567\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientName").value("Fatou Ndiaye"))
                .andExpect(jsonPath("$.trackingToken").value(oldToken))
                .andExpect(jsonPath("$.confirmationCode").value("123456"));

        BidEntity saved = bidRepository.findById(bid.getId()).orElseThrow();
        assertThat(saved.getRecipientPhone()).isEqualTo("+221 77 123 45 67");
        assertThat(revokedTokenRepository.existsByToken(oldToken)).isFalse();
        assertThat(auditPayloads(bid, "RECIPIENT_NAME_UPDATED")).hasSize(1);
        assertThat(auditPayloads(bid, "RECIPIENT_CHANGED")).isEmpty();
    }

    /** L'index unique ne porte plus que sur les liens actifs : un nouveau lien suit l'ancien. */
    @Test
    void softDeletedLink_doesNotBlockANewLinkOnTheSameBid() {
        BidEntity bid = persistBid(BidStatus.ACCEPTED);
        BidRecipientLinkEntity old = linkRepository.save(new BidRecipientLinkEntity(bid.getId(), oldRecipient.getId()));
        old.softDelete();
        linkRepository.save(old);

        UserEntity newRecipient = persistUser("Binta", Role.SENDER);
        linkRepository.saveAndFlush(new BidRecipientLinkEntity(bid.getId(), newRecipient.getId()));

        assertThat(linkRepository.findByBidId(bid.getId())).get()
                .extracting(BidRecipientLinkEntity::getRecipientUserId).isEqualTo(newRecipient.getId());
        assertThat(linkRepository.existsByBidId(bid.getId())).isTrue();
    }

    // ── Refus ───────────────────────────────────────────────────────────────

    @Test
    void deliveredParcel_409ProblemDetail() throws Exception {
        BidEntity bid = persistBid(BidStatus.COMPLETED);
        change(bid, sender, "{\"recipientName\":\"Awa\",\"recipientPhone\":\"+221781112233\"}")
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value(endsWith("recipient-change-not-allowed")));
    }

    @Test
    void travelerOrStranger_403() throws Exception {
        BidEntity bid = persistBid(BidStatus.IN_TRANSIT);
        change(bid, traveler, "{\"recipientName\":\"Awa\",\"recipientPhone\":\"+221781112233\"}")
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        change(bid, oldRecipient, "{\"recipientName\":\"Awa\",\"recipientPhone\":\"+221781112233\"}")
                .andExpect(status().isForbidden());
        assertThat(bidRepository.findById(bid.getId()).orElseThrow().getRecipientPhone())
                .isEqualTo("+221 77 123 45 67");
    }

    @Test
    void unauthenticated_isRejected() throws Exception {
        BidEntity bid = persistBid(BidStatus.IN_TRANSIT);
        int status = mockMvc.perform(put("/bids/{id}/recipient", bid.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientName\":\"Awa\",\"recipientPhone\":\"+221781112233\"}"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isIn(401, 403);
    }

    @Test
    void invalidBody_422_malformedBody_400() throws Exception {
        BidEntity bid = persistBid(BidStatus.IN_TRANSIT);
        String longName = "a".repeat(101);
        for (String body : new String[]{
                "{\"recipientName\":\"  \",\"recipientPhone\":\"+221781112233\"}",
                "{\"recipientName\":\"" + longName + "\",\"recipientPhone\":\"+221781112233\"}",
                "{\"recipientName\":\"Awa\",\"recipientPhone\":\"0781112233\"}",
                "{\"recipientName\":\"Awa\",\"recipientPhone\":\"+0781112233\"}",
                "{\"recipientName\":\"Awa\",\"recipientPhone\":\"+221 78 111 22 33\"}",
                "{\"recipientName\":\"Awa\"}"}) {
            change(bid, sender, body)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        }
        change(bid, sender, "{not json")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        assertThat(bidRepository.findById(bid.getId()).orElseThrow().getTrackingToken())
                .isEqualTo(bid.getTrackingToken());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private List<String> auditPayloads(BidEntity bid, String action) {
        return jdbcTemplate.queryForList(
                "SELECT CAST(payload AS VARCHAR) FROM audit_log WHERE entity_type = 'BID' AND action = ? "
                        + "AND entity_id = ? AND actor_id = ?",
                String.class, action, bid.getId(), sender.getId());
    }

    private ResultActions change(BidEntity bid, UserEntity caller, String body) throws Exception {
        return mockMvc.perform(put("/bids/{id}/recipient", bid.getId())
                .with(authentication(as(caller)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static UsernamePasswordAuthenticationToken as(UserEntity user) {
        return new UsernamePasswordAuthenticationToken(user.getFirebaseUid(), null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER"), new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private UserEntity persistUser(String firstName, Role role) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-recipient-change-" + UUID.randomUUID());
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
