package com.yadony.api.matching.reception;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.RecipientAccount;
import com.yadony.api.common.RecipientAccountResolver;
import com.yadony.api.common.YadonyBusinessException;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code PUT /bids/{bidId}/recipient} avec {@code recipientUsername} de bout en bout : mêmes effets qu'un changement
 * de numéro (jeton et code renouvelés, ancien lien retiré, événement), compte destinataire posé, numéro nul, aucune
 * fuite du numéro. Le dépôt est espionné pour {@code findByIdForUpdate} (clause refusée par H2), comme dans
 * {@link RecipientChangeIntegrationTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class RecipientChangeUsernameIT {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @MockitoSpyBean BidRepository bidRepository;
    @Autowired BidRecipientLinkRepository linkRepository;
    @Autowired RevokedTrackingTokenRepository revokedTokenRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockitoBean RecipientAccountResolver resolver;

    private UserEntity sender;
    private UserEntity traveler;
    private UserEntity oldRecipient;
    private UserEntity account;
    private AnnouncementEntity announcement;

    @BeforeEach
    void seed() {
        doAnswer(inv -> bidRepository.findById(inv.getArgument(0)))
                .when(bidRepository).findByIdForUpdate(any());
        sender = persistUser("Awa", Role.SENDER);
        traveler = persistUser("Moussa", Role.TRAVELER);
        oldRecipient = persistUser("Fatou", Role.SENDER);
        account = persistUser("Binta", Role.SENDER);
        announcement = persistAnnouncement();
        when(resolver.resolve(sender.getId(), "binta.sow")).thenReturn(new RecipientAccount(account.getId(), "binta.sow"));
    }

    @Test
    void numberToUsername_rotatesTokenAndCode_dropsOldLink_noPhoneLeak() throws Exception {
        BidEntity bid = persistBid(BidStatus.IN_TRANSIT);
        String oldToken = bid.getTrackingToken();
        BidRecipientLinkEntity oldLink = linkRepository.save(new BidRecipientLinkEntity(bid.getId(), oldRecipient.getId()));

        change(bid, sender, "{\"recipientUsername\":\"binta.sow\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientName").value("@binta.sow"))
                .andExpect(jsonPath("$.trackingToken").value(not(oldToken)))
                .andExpect(jsonPath("$.confirmationCode").value(not("123456")))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("123 45 67"))))
                .andExpect(content().string(not(org.hamcrest.Matchers.containsString("1234567"))));

        BidEntity saved = bidRepository.findById(bid.getId()).orElseThrow();
        assertThat(saved.getRecipientUserId()).isEqualTo(account.getId());
        assertThat(saved.getRecipientPhone()).isNull();
        assertThat(saved.getRecipientName()).isEqualTo("@binta.sow");
        assertThat(saved.getTrackingToken()).isNotEqualTo(oldToken);
        assertThat(saved.getConfirmationCode()).matches("\\d{6}").isNotEqualTo("123456");
        assertThat(saved.isConfirmationCodePublicEnabled()).isFalse();
        assertThat(revokedTokenRepository.existsByToken(oldToken)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM bid_recipient_links WHERE id = ?", Boolean.class, oldLink.getId()))
                .isTrue();
        // L'ancien destinataire perd l'accès immédiatement.
        mockMvc.perform(get("/receptions/{id}", bid.getId()).with(authentication(as(oldRecipient))))
                .andExpect(status().isNotFound());

        List<String> changed = auditPayloads(bid, "RECIPIENT_CHANGED");
        assertThat(changed).hasSize(1);
        assertThat(changed.get(0)).contains(account.getId().toString())
                .doesNotContain("781112233").doesNotContain("123 45 67").doesNotContain("1234567");
        List<String> designated = auditPayloads(bid, "RECIPIENT_DESIGNATED_BY_USERNAME");
        assertThat(designated).hasSize(1);
        assertThat(designated.get(0)).contains(account.getId().toString()).doesNotContain("1234567");
    }

    @Test
    void usernameToNumber_clearsTheAccount() throws Exception {
        BidEntity bid = persistBid(BidStatus.ACCEPTED);
        bid.setRecipientUserId(account.getId());
        bid.setRecipientName("@binta.sow");
        bid.setRecipientPhone(null);
        bidRepository.save(bid);

        change(bid, sender, "{\"recipientName\":\"Awa Sow\",\"recipientPhone\":\"+221781112233\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientName").value("Awa Sow"));

        BidEntity saved = bidRepository.findById(bid.getId()).orElseThrow();
        assertThat(saved.getRecipientUserId()).isNull();
        assertThat(saved.getRecipientPhone()).isEqualTo("+221781112233");
        assertThat(saved.getTrackingToken()).isNotEqualTo(bid.getTrackingToken());
    }

    @Test
    void sameUsername_keepsTokenAndCode() throws Exception {
        BidEntity bid = persistBid(BidStatus.IN_TRANSIT);
        bid.setRecipientUserId(account.getId());
        bid.setRecipientName("@ancien");
        bid.setRecipientPhone(null);
        bidRepository.save(bid);

        change(bid, sender, "{\"recipientUsername\":\"binta.sow\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientName").value("@binta.sow"))
                .andExpect(jsonPath("$.trackingToken").value(bid.getTrackingToken()));

        assertThat(revokedTokenRepository.existsByToken(bid.getTrackingToken())).isFalse();
        assertThat(bidRepository.findById(bid.getId()).orElseThrow().getConfirmationCode()).isEqualTo("123456");
        assertThat(auditPayloads(bid, "RECIPIENT_CHANGED")).isEmpty();
    }

    @Test
    void refusals_travelerSelfUnknown_modeConflict_required_status_noWrite() throws Exception {
        BidEntity bid = persistBid(BidStatus.IN_TRANSIT);
        when(resolver.resolve(sender.getId(), "voyageur")).thenReturn(new RecipientAccount(traveler.getId(), "voyageur"));
        when(resolver.resolve(sender.getId(), "moi")).thenThrow(YadonyBusinessException.localized(
                HttpStatus.UNPROCESSABLE_ENTITY, "recipient-invitation-self", "problem.recipient-invitation-self"));
        when(resolver.resolve(sender.getId(), "inconnu")).thenThrow(YadonyBusinessException.localized(
                HttpStatus.NOT_FOUND, "user-not-found", "problem.user-not-found"));

        change(bid, sender, "{\"recipientUsername\":\"voyageur\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value(endsWith("recipient-is-traveler")));
        change(bid, sender, "{\"recipientUsername\":\"moi\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value(endsWith("recipient-invitation-self")));
        change(bid, sender, "{\"recipientUsername\":\"inconnu\"}")
                .andExpect(status().isNotFound());
        change(bid, sender, "{\"recipientUsername\":\"binta.sow\",\"recipientName\":\"Awa\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value(endsWith("recipient-mode-conflict")));
        change(bid, sender, "{}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value(endsWith("recipient-required")));
        change(bid, traveler, "{\"recipientUsername\":\"binta.sow\"}")
                .andExpect(status().isForbidden());
        BidEntity done = persistBid(BidStatus.COMPLETED);
        change(done, sender, "{\"recipientUsername\":\"binta.sow\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(endsWith("recipient-change-not-allowed")));

        BidEntity saved = bidRepository.findById(bid.getId()).orElseThrow();
        assertThat(saved.getRecipientUserId()).isNull();
        assertThat(saved.getRecipientPhone()).isEqualTo("+221 77 123 45 67");
        assertThat(saved.getTrackingToken()).isEqualTo(bid.getTrackingToken());
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
