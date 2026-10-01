package com.yadony.api.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.yadony.api.matching.reception.BidRecipientLinkEntity;
import com.yadony.api.matching.reception.BidRecipientLinkRepository;
import com.yadony.api.matching.reception.ReceptionLinkStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /conversations/bid/{bidId}/recipient} de bout en bout (lot 3C) : accès, rendu,
 * liste, révocation au changement de destinataire, et aucun effet sur la conversation
 * expéditeur ↔ voyageur. Firestore est désactivé au profil test.
 *
 * <p>{@code findByIdForUpdate} (changement de destinataire) est refusé par H2 : le dépôt est
 * espionné et délègue à {@code findById}, comme dans RecipientChangeIntegrationTest.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class RecipientConversationIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @MockitoSpyBean BidRepository bidRepository;
    @Autowired BidRecipientLinkRepository linkRepository;
    @Autowired ConversationRepository conversationRepository;
    @Autowired RecipientConversationService recipientConversationService;

    private UserEntity sender;
    private UserEntity traveler;
    private UserEntity recipient;
    private BidEntity bid;

    @BeforeEach
    void seed() {
        doAnswer(inv -> bidRepository.findById(inv.getArgument(0)))
                .when(bidRepository).findByIdForUpdate(any());
        sender = persistUser("Awa");
        traveler = persistUser("Moussa");
        recipient = persistUser("Fatou");
        bid = persistBid(persistAnnouncement(), BidStatus.IN_TRANSIT);
    }

    // ── Création et accès ──────────────────────────────────────────────────

    @Test
    void traveler_opensTheConversation_recipientGetsTheSameOne() throws Exception {
        confirmedLink(recipient);

        JsonNode byTraveler = json(openRecipient(traveler)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("RECIPIENT_TRAVELER"))
                .andExpect(jsonPath("$.bidId").value(bid.getId().toString()))
                .andExpect(jsonPath("$.firestoreConversationId").value("rconv_" + bid.getId()))
                .andExpect(jsonPath("$.otherParticipant.id").value(recipient.getId().toString()))
                .andExpect(jsonPath("$.otherParticipant.role").value("Destinataire"))
                .andExpect(jsonPath("$.viewerRole").value("TRAVELER"))
                .andExpect(jsonPath("$.otherParticipant.phoneAvailable").value(false))
                .andExpect(jsonPath("$.readOnly").value(false)));

        openRecipient(recipient)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(byTraveler.get("id").asText()))
                .andExpect(jsonPath("$.otherParticipant.id").value(traveler.getId().toString()))
                .andExpect(jsonPath("$.otherParticipant.role").value("Voyageur"))
                .andExpect(jsonPath("$.viewerRole").value("RECIPIENT"))
                .andExpect(jsonPath("$.otherParticipant.phoneAvailable").value(false));

        ConversationEntity saved = conversationRepository.findById(UUID.fromString(byTraveler.get("id").asText())).orElseThrow();
        assertThat(saved.getKind()).isEqualTo(ConversationKind.RECIPIENT_TRAVELER);
        assertThat(saved.participantAId()).isEqualTo(recipient.getId());
        assertThat(saved.getTravelerId()).isEqualTo(traveler.getId());
    }

    @Test
    void recipientWithoutAnyRole_canOpenIt() throws Exception {
        confirmedLink(recipient);

        mockMvc.perform(get("/conversations/bid/{bidId}/recipient", bid.getId())
                        .with(authentication(as(recipient, List.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("RECIPIENT_TRAVELER"));
    }

    @Test
    void sender_thirdParty_andPendingLink_get403ProblemDetail() throws Exception {
        confirmedLink(recipient);
        expectForbidden(openRecipient(sender));
        expectForbidden(openRecipient(persistUser("Tiers")));

        BidEntity pendingBid = persistBid(persistAnnouncement(), BidStatus.IN_TRANSIT);
        linkRepository.save(new BidRecipientLinkEntity(pendingBid.getId(), recipient.getId()));
        expectForbidden(mockMvc.perform(get("/conversations/bid/{bidId}/recipient", pendingBid.getId())
                .with(authentication(as(traveler)))));
        expectForbidden(mockMvc.perform(get("/conversations/bid/{bidId}/recipient", pendingBid.getId())
                .with(authentication(as(recipient)))));
    }

    @Test
    void unknownBid_404() throws Exception {
        mockMvc.perform(get("/conversations/bid/{bidId}/recipient", UUID.randomUUID())
                        .with(authentication(as(traveler))))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    // ── Liste ───────────────────────────────────────────────────────────────

    @Test
    void list_showsTheConversationToBothParticipants_neverToTheSender() throws Exception {
        confirmedLink(recipient);
        String id = json(openRecipient(traveler).andExpect(status().isOk())).get("id").asText();

        mockMvc.perform(get("/conversations").with(authentication(as(traveler))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(hasItem(id)))
                .andExpect(jsonPath("$.content[?(@.id == '" + id + "')].kind").value(hasItem("RECIPIENT_TRAVELER")))
                .andExpect(jsonPath("$.content[?(@.id == '" + id + "')].viewerRole").value(hasItem("TRAVELER")));
        mockMvc.perform(get("/conversations").with(authentication(as(recipient))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(hasItem(id)))
                .andExpect(jsonPath("$.content[?(@.id == '" + id + "')].viewerRole").value(hasItem("RECIPIENT")));
        mockMvc.perform(get("/conversations").with(authentication(as(sender))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(not(hasItem(id))));
        // Accès par id : la garde participant existante couvre le destinataire, pas l'expéditeur.
        mockMvc.perform(get("/conversations/{id}", id).with(authentication(as(recipient))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("RECIPIENT_TRAVELER"))
                .andExpect(jsonPath("$.viewerRole").value("RECIPIENT"));
        mockMvc.perform(get("/conversations/{id}", id).with(authentication(as(traveler))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewerRole").value("TRAVELER"));
        mockMvc.perform(get("/conversations/{id}", id).with(authentication(as(sender))))
                .andExpect(status().isForbidden());
    }

    // ── Conversation expéditeur ↔ voyageur inchangée ─────────────────────────

    @Test
    void senderTravelerConversation_isUnaffected() throws Exception {
        confirmedLink(recipient);
        String recipientConvId = json(openRecipient(traveler).andExpect(status().isOk())).get("id").asText();

        String senderConvId = json(mockMvc.perform(get("/conversations/bid/{bidId}", bid.getId())
                        .with(authentication(as(sender))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("SENDER_TRAVELER"))
                .andExpect(jsonPath("$.viewerRole").doesNotExist())
                .andExpect(jsonPath("$.firestoreConversationId").value("conv_" + bid.getId()))
                .andExpect(jsonPath("$.otherParticipant.role").value("Voyageur"))
                .andExpect(jsonPath("$.otherParticipant.phoneAvailable").value(true)))
                .get("id").asText();
        assertThat(senderConvId).isNotEqualTo(recipientConvId);

        // Le voyageur retrouve la conversation expéditeur par le bid, jamais celle du destinataire.
        mockMvc.perform(get("/conversations/bid/{bidId}", bid.getId()).with(authentication(as(traveler))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(senderConvId))
                .andExpect(jsonPath("$.otherParticipant.role").value("Expéditeur"));
        // Le destinataire n'est pas partie à la conversation expéditeur.
        mockMvc.perform(get("/conversations/bid/{bidId}", bid.getId()).with(authentication(as(recipient))))
                .andExpect(status().isForbidden());
        assertThat(conversationRepository.findByBidId(bid.getId())).get()
                .extracting(ConversationEntity::getId).hasToString(senderConvId);
    }

    // ── Révocation ─────────────────────────────────────────────────────────

    @Test
    void recipientChange_closesTheConversation_andTheNewRecipientGetsANewOne() throws Exception {
        BidRecipientLinkEntity link = confirmedLink(recipient);
        String oldId = json(openRecipient(recipient).andExpect(status().isOk())).get("id").asText();

        // Changement de destinataire (lot 3A) : le lien est soft-deleted, puis la
        // révocation (écouteur AFTER_COMMIT, appelée ici directement) ferme la conversation.
        link.softDelete();
        linkRepository.saveAndFlush(link);
        assertThat(recipientConversationService.revokeStale(bid.getId())).isEqualTo(1);

        mockMvc.perform(get("/conversations").with(authentication(as(recipient))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id").value(not(hasItem(oldId))));
        mockMvc.perform(get("/conversations/{id}", oldId).with(authentication(as(recipient))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(endsWith("conversation-not-found")));
        expectForbidden(openRecipient(recipient));

        // Le voyageur la garde, en lecture seule.
        mockMvc.perform(get("/conversations/{id}", oldId).with(authentication(as(traveler))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readOnly").value(true));

        // Nouveau destinataire confirmé : nouvelle conversation, id Firestore distinct.
        UserEntity newRecipient = persistUser("Binta");
        confirmedLink(newRecipient);
        openRecipient(traveler)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(not(oldId)))
                .andExpect(jsonPath("$.otherParticipant.id").value(newRecipient.getId().toString()))
                .andExpect(jsonPath("$.firestoreConversationId").value("rconv_" + bid.getId() + "_2"));
        openRecipient(newRecipient).andExpect(status().isOk());
    }

    @Test
    void changingTheRecipientNumber_closesTheConversationThroughTheEvent() throws Exception {
        confirmedLink(recipient);
        String id = json(openRecipient(traveler).andExpect(status().isOk())).get("id").asText();

        mockMvc.perform(put("/bids/{id}/recipient", bid.getId())
                        .with(authentication(as(sender)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipientName\":\"Awa Sow\",\"recipientPhone\":\"+221781112233\"}"))
                .andExpect(status().isOk());

        // L'écouteur est @Async AFTER_COMMIT : attente bornée de la fermeture.
        UUID convId = UUID.fromString(id);
        long deadline = System.currentTimeMillis() + 10_000;
        while (!conversationRepository.findById(convId).orElseThrow().isClosed()
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(conversationRepository.findById(convId).orElseThrow().isClosed()).isTrue();
        mockMvc.perform(get("/conversations/{id}", id).with(authentication(as(recipient))))
                .andExpect(status().isNotFound());
    }

    // ── Aides ───────────────────────────────────────────────────────────────

    private ResultActions openRecipient(UserEntity caller) throws Exception {
        return mockMvc.perform(get("/conversations/bid/{bidId}/recipient", bid.getId())
                .with(authentication(as(caller))));
    }

    private static void expectForbidden(ResultActions result) throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value(endsWith("recipient-conversation-forbidden")));
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private BidRecipientLinkEntity confirmedLink(UserEntity user) {
        BidRecipientLinkEntity link = new BidRecipientLinkEntity(bid.getId(), user.getId());
        link.respond(ReceptionLinkStatus.CONFIRMED, OffsetDateTime.now());
        return linkRepository.saveAndFlush(link);
    }

    private static UsernamePasswordAuthenticationToken as(UserEntity user) {
        return as(user, List.of(new SimpleGrantedAuthority("ROLE_SENDER"), new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private static UsernamePasswordAuthenticationToken as(UserEntity user, List<? extends GrantedAuthority> roles) {
        return new UsernamePasswordAuthenticationToken(user.getFirebaseUid(), null, new ArrayList<>(roles));
    }

    private UserEntity persistUser(String firstName) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-recipient-conv-" + UUID.randomUUID());
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

    private BidEntity persistBid(AnnouncementEntity announcement, BidStatus status) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(announcement.getId());
        b.setSenderId(sender.getId());
        b.setWeightKg(new BigDecimal("3.00"));
        b.setStatus(status);
        b.setRecipientName("Fatou Diop");
        b.setRecipientPhone("+221 77 123 45 67");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        b.setTrackingNumber("DON-" + suffix);
        b.setTrackingToken(UUID.randomUUID().toString());
        b.setConfirmationCode("123456");
        return bidRepository.save(b);
    }
}
