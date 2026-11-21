package com.yadony.api.addressbook.invitation;

import com.yadony.api.addressbook.recipient.RecipientEntity;
import com.yadony.api.addressbook.recipient.RecipientRepository;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.RecipientTrust;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class RecipientInvitationControllerIntegrationTest {

    private static final String INVITER_UID = "firebase-invit-inviter";
    private static final String INVITEE_UID = "firebase-invit-invitee";
    private static final String INVITEE_PHONE = "+221771234512";
    private static final String INVITEE_EMAIL = "fatou.sow@gmail.com";
    private static final String SENT_BODY = "{\"status\":\"SENT\"}";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired RecipientRepository recipientRepository;
    @Autowired RecipientInvitationRepository invitationRepository;
    @Autowired RecipientTrust recipientTrust;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean FirebaseContactService firebaseContact;

    private UserEntity inviter;
    private UserEntity invitee;

    @BeforeEach
    void seed() {
        invitationRepository.deleteAll();
        recipientRepository.deleteAll();
        // Suppression physique : un compte supprimé en douceur garderait son identifiant (unique).
        jdbc.update("DELETE FROM user_blocks WHERE blocker_id IN (SELECT id FROM users WHERE firebase_uid IN (?, ?))",
                INVITER_UID, INVITEE_UID);
        jdbc.update("DELETE FROM user_roles WHERE user_id IN (SELECT id FROM users WHERE firebase_uid IN (?, ?))",
                INVITER_UID, INVITEE_UID);
        jdbc.update("DELETE FROM users WHERE firebase_uid IN (?, ?)", INVITER_UID, INVITEE_UID);
        inviter = persistUser(INVITER_UID, "Awa", "Diallo");
        invitee = persistUser(INVITEE_UID, "Fatou", "Sow");
        when(firebaseContact.findUidByPhone(anyString())).thenReturn(Optional.empty());
        when(firebaseContact.findUidByEmail(anyString())).thenReturn(Optional.empty());
        when(firebaseContact.findUidByPhone(INVITEE_PHONE)).thenReturn(Optional.of(INVITEE_UID));
        when(firebaseContact.findUidByEmail(INVITEE_EMAIL)).thenReturn(Optional.of(INVITEE_UID));
        when(firebaseContact.getContact(anyString())).thenReturn(FirebaseContactService.Contact.EMPTY);
        when(firebaseContact.getContact(INVITEE_UID))
                .thenReturn(new FirebaseContactService.Contact(INVITEE_PHONE, INVITEE_EMAIL));
    }

    private UserEntity persistUser(String uid, String first, String last) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(uid);
        u.setFirstName(first);
        u.setLastName(last);
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        u.setRoles(new HashSet<>(List.of(Role.SENDER)));
        return userRepository.save(u);
    }

    private static UsernamePasswordAuthenticationToken as(String uid) {
        return new UsernamePasswordAuthenticationToken(uid, null, List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    private MockHttpServletRequestBuilder invite(String json) {
        return post("/recipient-invitations").with(authentication(as(INVITER_UID)))
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private RecipientInvitationEntity onlyInvitation() {
        List<RecipientInvitationEntity> all = invitationRepository.findAll();
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    // ── POST : réponse indiscernable ────────────────────────────────────────

    @Test
    void send_sameResponseForAccountNoAccountDuplicateAndSelf() throws Exception {
        // Compte existant
        mockMvc.perform(invite("{\"phone\":\"" + INVITEE_PHONE + "\"}"))
                .andExpect(status().isAccepted()).andExpect(content().json(SENT_BODY, true));
        // Doublon
        mockMvc.perform(invite("{\"phone\":\"+221 77 123 45 12\"}"))
                .andExpect(status().isAccepted()).andExpect(content().json(SENT_BODY, true));
        // Aucun compte
        mockMvc.perform(invite("{\"email\":\"Inconnu@Example.com\"}"))
                .andExpect(status().isAccepted()).andExpect(content().json(SENT_BODY, true));
        // Soi-même
        when(firebaseContact.findUidByPhone("+33600000001")).thenReturn(Optional.of(INVITER_UID));
        mockMvc.perform(invite("{\"phone\":\"+33600000001\"}"))
                .andExpect(status().isAccepted()).andExpect(content().json(SENT_BODY, true));

        List<RecipientInvitationEntity> all = invitationRepository.findAll();
        assertThat(all).hasSize(2);
        assertThat(all).filteredOn(i -> i.getChannel() == InvitationChannel.PHONE).singleElement()
                .satisfies(i -> assertThat(i.getInviteeUserId()).isEqualTo(invitee.getId()));
        assertThat(all).filteredOn(i -> i.getChannel() == InvitationChannel.EMAIL).singleElement()
                .satisfies(i -> {
                    assertThat(i.getInviteeUserId()).isNull();
                    assertThat(i.getTargetHash()).isEqualTo(InvitationTargets.hash("inconnu@example.com"));
                });
    }

    @Test
    void send_invalidBodies_return422ProblemDetail() throws Exception {
        for (String body : List.of("{}", "{\"phone\":\"771234512\"}", "{\"email\":\"nope\"}",
                "{\"phone\":\"" + INVITEE_PHONE + "\",\"email\":\"" + INVITEE_EMAIL + "\"}")) {
            mockMvc.perform(invite(body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("recipient-invitation-invalid-target"));
        }
        assertThat(invitationRepository.findAll()).isEmpty();
    }

    @Test
    void send_quotaOf20Per24h_returns429() throws Exception {
        for (int i = 0; i < RecipientInvitationService.DAILY_QUOTA; i++) {
            mockMvc.perform(invite("{\"phone\":\"+2217000000" + String.format("%02d", i) + "\"}"))
                    .andExpect(status().isAccepted());
        }
        mockMvc.perform(invite("{\"phone\":\"+221709999999\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("recipient-invitation-quota"));
    }

    @Test
    void neverStoresTheTargetInClear_inTableNorAudit() throws Exception {
        mockMvc.perform(invite("{\"phone\":\"" + INVITEE_PHONE + "\"}")).andExpect(status().isAccepted());
        mockMvc.perform(invite("{\"email\":\"autre.personne@gmail.com\"}")).andExpect(status().isAccepted());

        String rows = jdbc.queryForList("SELECT * FROM recipient_invitations").toString();
        assertThat(rows).doesNotContain("771234512").doesNotContain("autre.personne");
        String audits = jdbc.queryForList(
                "SELECT CAST(payload AS VARCHAR) FROM audit_log WHERE entity_type = 'RECIPIENT_INVITATION'",
                String.class).toString();
        assertThat(audits).contains("PHONE").doesNotContain("771234512").doesNotContain("autre.personne");
    }

    // ── Côté inviteur : liste envoyée ───────────────────────────────────────

    @Test
    void sent_showsMaskedTargetAndPendingEvenWhenDeclined() throws Exception {
        mockMvc.perform(invite("{\"phone\":\"" + INVITEE_PHONE + "\"}")).andExpect(status().isAccepted());
        mockMvc.perform(invite("{\"email\":\"awa.k@gmail.com\"}")).andExpect(status().isAccepted());
        RecipientInvitationEntity phoneInvitation = invitationRepository.findAll().stream()
                .filter(i -> i.getChannel() == InvitationChannel.PHONE).findFirst().orElseThrow();
        mockMvc.perform(post("/recipient-invitations/{id}/decline", phoneInvitation.getId())
                        .with(authentication(as(INVITEE_UID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"));

        mockMvc.perform(get("/recipient-invitations/sent").with(authentication(as(INVITER_UID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.channel == 'PHONE')].maskedTarget").value("+221 •• •• •• 12"))
                .andExpect(jsonPath("$[?(@.channel == 'PHONE')].status").value("PENDING"))
                .andExpect(jsonPath("$[?(@.channel == 'EMAIL')].maskedTarget").value("a••••@gmail.com"));
    }

    // ── Canal USERNAME ──────────────────────────────────────────────────────

    private void giveUsername(UserEntity user, String username) {
        UserEntity fresh = userRepository.findById(user.getId()).orElseThrow();
        fresh.setUsername(username);
        userRepository.save(fresh);
    }

    @Test
    void sendByUsername_found_202_onePendingRowWithInvitee() throws Exception {
        giveUsername(invitee, "fatou.sow");

        mockMvc.perform(invite("{\"username\":\"Fatou.Sow\"}"))
                .andExpect(status().isAccepted()).andExpect(content().json(SENT_BODY, true));

        RecipientInvitationEntity row = onlyInvitation();
        assertThat(row.getChannel()).isEqualTo(InvitationChannel.USERNAME);
        assertThat(row.getStatus()).isEqualTo(InvitationStatus.PENDING);
        assertThat(row.getInviteeUserId()).isEqualTo(invitee.getId());
        assertThat(row.getMaskedTarget()).isEqualTo("@fatou.sow");
    }

    @Test
    void sendByUsername_unknown_404_nothingWritten() throws Exception {
        mockMvc.perform(invite("{\"username\":\"personne.ici\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("user-not-found"));
        mockMvc.perform(invite("{\"username\":\"!!\"}")).andExpect(status().isNotFound());
        assertThat(invitationRepository.findAll()).isEmpty();
    }

    @Test
    void sendByUsername_twice_secondCreatesNothing() throws Exception {
        giveUsername(invitee, "fatou.sow");
        mockMvc.perform(invite("{\"username\":\"fatou.sow\"}")).andExpect(status().isAccepted());
        mockMvc.perform(invite("{\"username\":\"fatou.sow\"}"))
                .andExpect(status().isAccepted()).andExpect(content().json(SENT_BODY, true));
        assertThat(invitationRepository.findAll()).hasSize(1);
    }

    @Test
    void sendByUsername_severalFieldsOrNone_422() throws Exception {
        giveUsername(invitee, "fatou.sow");
        for (String body : List.of("{\"username\":\"fatou.sow\",\"phone\":\"" + INVITEE_PHONE + "\"}",
                "{\"username\":\"fatou.sow\",\"email\":\"" + INVITEE_EMAIL + "\"}", "{\"username\":\" \"}")) {
            mockMvc.perform(invite(body)).andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("recipient-invitation-invalid-target"));
        }
        assertThat(invitationRepository.findAll()).isEmpty();
    }

    @Test
    void sendByUsername_inviteeBlockedInviter_404() throws Exception {
        giveUsername(invitee, "fatou.sow");
        jdbc.update("INSERT INTO user_blocks (id, blocker_id, blocked_id, created_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                UUID.randomUUID(), invitee.getId(), inviter.getId());
        try {
            mockMvc.perform(invite("{\"username\":\"fatou.sow\"}")).andExpect(status().isNotFound());
            assertThat(invitationRepository.findAll()).isEmpty();
        } finally {
            jdbc.update("DELETE FROM user_blocks WHERE blocker_id = ?", invitee.getId());
        }
    }

    @Test
    void sendByUsername_self_422() throws Exception {
        giveUsername(inviter, "awa.diallo");
        mockMvc.perform(invite("{\"username\":\"awa.diallo\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("recipient-invitation-self"));
        assertThat(invitationRepository.findAll()).isEmpty();
    }

    @Test
    void sendByUsername_quotaOf20_returns429_andUnknownDoesNotConsumeIt() throws Exception {
        giveUsername(invitee, "fatou.sow");
        for (int i = 0; i < RecipientInvitationService.DAILY_QUOTA - 1; i++) {
            mockMvc.perform(invite("{\"phone\":\"+2217000000" + String.format("%02d", i) + "\"}"))
                    .andExpect(status().isAccepted());
        }
        mockMvc.perform(invite("{\"username\":\"inconnu.total\"}")).andExpect(status().isNotFound());
        mockMvc.perform(invite("{\"username\":\"fatou.sow\"}")).andExpect(status().isAccepted());
        mockMvc.perform(invite("{\"username\":\"fatou.sow\"}")).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("recipient-invitation-quota"));
    }

    @Test
    void usernameSend_sharesTheLookupBucketOf30PerHour() throws Exception {
        for (int i = 0; i < LookupRateLimiter.MAX_PER_HOUR - 1; i++) {
            mockMvc.perform(get("/recipient-invitations/lookup").param("username", "inconnu.total")
                    .with(authentication(as(INVITER_UID)))).andExpect(status().isNotFound());
        }
        // 30e appel du seau : un envoi, qui compte comme une recherche
        mockMvc.perform(invite("{\"username\":\"inconnu.total\"}")).andExpect(status().isNotFound());
        mockMvc.perform(invite("{\"username\":\"inconnu.total\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("recipient-lookup-quota"));
        mockMvc.perform(get("/recipient-invitations/lookup").param("username", "inconnu.total")
                .with(authentication(as(INVITER_UID)))).andExpect(status().isTooManyRequests());
        assertThat(invitationRepository.findAll()).isEmpty();
    }

    @Test
    void sent_usernameInvitation_showsCurrentUsernameAfterRename() throws Exception {
        giveUsername(invitee, "fatou.sow");
        mockMvc.perform(invite("{\"username\":\"fatou.sow\"}")).andExpect(status().isAccepted());
        giveUsername(invitee, "fatou.nouveau");

        mockMvc.perform(get("/recipient-invitations/sent").with(authentication(as(INVITER_UID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].channel").value("USERNAME"))
                .andExpect(jsonPath("$[0].maskedTarget").value("@fatou.nouveau"));
    }

    // ── Côté invité : rattrapage, accept, 409, revoke ───────────────────────

    @Test
    void incoming_catchesUpInvitationSentBeforeAccountByHash() throws Exception {
        // Invitation envoyée quand le compte n'existait pas encore (lookup vide).
        when(firebaseContact.findUidByEmail(INVITEE_EMAIL)).thenReturn(Optional.empty());
        mockMvc.perform(invite("{\"email\":\"" + INVITEE_EMAIL.toUpperCase() + "\"}")).andExpect(status().isAccepted());
        assertThat(onlyInvitation().getInviteeUserId()).isNull();

        mockMvc.perform(get("/recipient-invitations/incoming").with(authentication(as(INVITEE_UID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].inviterFirstName").value("Awa"))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[0].createdAt").exists());
        assertThat(onlyInvitation().getInviteeUserId()).isEqualTo(invitee.getId());
    }

    @Test
    void accept_addsInviteeToInviterAddressBook_linkedOnYadony_andTrustsSender() throws Exception {
        mockMvc.perform(invite("{\"phone\":\"" + INVITEE_PHONE + "\"}")).andExpect(status().isAccepted());
        UUID id = onlyInvitation().getId();

        mockMvc.perform(post("/recipient-invitations/{id}/accept", id).with(authentication(as(INVITEE_UID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        RecipientInvitationEntity accepted = onlyInvitation();
        assertThat(accepted.getStatus()).isEqualTo(InvitationStatus.ACCEPTED);
        assertThat(accepted.getRecipientId()).isNotNull();
        RecipientEntity entry = recipientRepository.findById(accepted.getRecipientId()).orElseThrow();
        assertThat(entry.getUserId()).isEqualTo(inviter.getId());
        assertThat(entry.getFullName()).isEqualTo("Fatou Sow");
        assertThat(entry.getPhoneE164()).isEqualTo(INVITEE_PHONE);
        assertThat(recipientTrust.isTrusted(inviter.getId(), invitee.getId())).isTrue();
        assertThat(recipientTrust.isTrusted(invitee.getId(), inviter.getId())).isFalse();

        mockMvc.perform(get("/addressbook/recipients").with(authentication(as(INVITER_UID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].linkedOnYadony").value(true));
        mockMvc.perform(get("/recipient-invitations/sent").with(authentication(as(INVITER_UID))))
                .andExpect(jsonPath("$[0].status").value("ACCEPTED"));
        mockMvc.perform(get("/recipient-invitations/incoming").with(authentication(as(INVITEE_UID))))
                .andExpect(jsonPath("$[0].status").value("ACCEPTED"));

        // L'invité retire son accord : l'entrée reste, elle n'est plus liée.
        mockMvc.perform(delete("/recipient-invitations/{id}", id).with(authentication(as(INVITEE_UID))))
                .andExpect(status().isNoContent());
        assertThat(onlyInvitation().getStatus()).isEqualTo(InvitationStatus.REVOKED);
        assertThat(recipientRepository.findById(entry.getId())).isPresent();
        assertThat(recipientTrust.isTrusted(inviter.getId(), invitee.getId())).isFalse();
        mockMvc.perform(get("/addressbook/recipients").with(authentication(as(INVITER_UID))))
                .andExpect(jsonPath("$[0].linkedOnYadony").value(false));
        mockMvc.perform(get("/recipient-invitations/sent").with(authentication(as(INVITER_UID))))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void accept_withoutPhone_returns409() throws Exception {
        mockMvc.perform(invite("{\"email\":\"" + INVITEE_EMAIL + "\"}")).andExpect(status().isAccepted());
        when(firebaseContact.getContact(INVITEE_UID)).thenReturn(new FirebaseContactService.Contact(null, INVITEE_EMAIL));

        mockMvc.perform(post("/recipient-invitations/{id}/accept", onlyInvitation().getId())
                        .with(authentication(as(INVITEE_UID))))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("recipient-invitation-phone-required"));
        assertThat(onlyInvitation().getStatus()).isEqualTo(InvitationStatus.PENDING);
    }

    @Test
    void inviterRevokes_andStrangersGet404() throws Exception {
        mockMvc.perform(invite("{\"phone\":\"" + INVITEE_PHONE + "\"}")).andExpect(status().isAccepted());
        UUID id = onlyInvitation().getId();

        // L'inviteur ne peut pas accepter à la place de l'invité.
        mockMvc.perform(post("/recipient-invitations/{id}/accept", id).with(authentication(as(INVITER_UID))))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/recipient-invitations/{id}", id).with(authentication(as(INVITER_UID))))
                .andExpect(status().isNoContent());
        assertThat(onlyInvitation().getStatus()).isEqualTo(InvitationStatus.REVOKED);
        mockMvc.perform(get("/recipient-invitations/incoming").with(authentication(as(INVITEE_UID))))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(delete("/recipient-invitations/{id}", id).with(authentication(as(INVITER_UID))))
                .andExpect(status().isNotFound());

        // Une nouvelle invitation reste possible après retrait.
        mockMvc.perform(invite("{\"phone\":\"" + INVITEE_PHONE + "\"}")).andExpect(status().isAccepted());
        assertThat(invitationRepository.findAll()).hasSize(2);
    }
    @Test
    void inviterDeletesTheLinkedEntry_revokesTheInvitation_andANewInvitationIsSent() throws Exception {
        // FLUTTER-8Z : supprimer l'entrée laissait l'invitation ACCEPTED vivante. L'inviteur
        // restait autorisé chez l'invité, et réinviter la même personne n'envoyait rien.
        mockMvc.perform(invite("{\"phone\":\"" + INVITEE_PHONE + "\"}")).andExpect(status().isAccepted());
        UUID id = onlyInvitation().getId();
        mockMvc.perform(post("/recipient-invitations/{id}/accept", id).with(authentication(as(INVITEE_UID))))
                .andExpect(status().isOk());
        UUID entryId = onlyInvitation().getRecipientId();

        mockMvc.perform(delete("/addressbook/recipients/{id}", entryId).with(authentication(as(INVITER_UID))))
                .andExpect(status().isNoContent());

        assertThat(onlyInvitation().getStatus()).isEqualTo(InvitationStatus.REVOKED);
        assertThat(recipientTrust.isTrusted(inviter.getId(), invitee.getId())).isFalse();
        mockMvc.perform(get("/recipient-invitations/incoming").with(authentication(as(INVITEE_UID))))
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(invite("{\"phone\":\"" + INVITEE_PHONE + "\"}")).andExpect(status().isAccepted());
        assertThat(invitationRepository.findAll())
                .extracting(RecipientInvitationEntity::getStatus)
                .containsExactlyInAnyOrder(InvitationStatus.REVOKED, InvitationStatus.PENDING);
        mockMvc.perform(get("/recipient-invitations/incoming").with(authentication(as(INVITEE_UID))))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }
}
