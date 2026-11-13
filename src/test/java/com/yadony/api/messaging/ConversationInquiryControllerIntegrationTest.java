package com.yadony.api.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.notifications.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /conversations/inquiry} de bout en bout (H2, Firestore simulé) : forme de la réponse,
 * refus KYC / annonce inconnue, visibilité dans les deux listes avant et après le premier message,
 * lecture et notification sans bid, identifiant Firestore aligné, photos refusées.
 */
@SpringBootTest(properties = "yadony.internal.secret=test-internal-secret")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ConversationInquiryControllerIntegrationTest {

    static final String CAP = "X-Dony-Capabilities";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired ConversationRepository conversationRepository;
    @Autowired InquiryInactivityScheduler inquiryInactivityScheduler;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean FirestoreService firestoreService;
    @MockitoBean NotificationDispatcher notificationDispatcher;

    UserEntity asker;
    UserEntity owner;
    AnnouncementEntity announcement;

    @BeforeEach
    void seed() {
        asker = persistUser("Fatou", KycStatus.VERIFIED);
        owner = persistUser("Moussa", KycStatus.VERIFIED);
        announcement = persistAnnouncement(owner);
    }

    private String body(UUID announcementId) throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of("announcementId", announcementId));
    }

    private UUID open() throws Exception {
        String json = mockMvc.perform(post("/conversations/inquiry").with(authentication(as(asker)))
                        .contentType(MediaType.APPLICATION_JSON).content(body(announcement.getId())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(json).get("id").asText());
    }

    @Test
    void open_returns200WithInquiryShape_andIsIdempotent() throws Exception {
        String json = mockMvc.perform(post("/conversations/inquiry").with(authentication(as(asker)))
                        .contentType(MediaType.APPLICATION_JSON).content(body(announcement.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("INQUIRY"))
                .andExpect(jsonPath("$.bidId").doesNotExist())
                .andExpect(jsonPath("$.announcementId").value(announcement.getId().toString()))
                .andExpect(jsonPath("$.inquiryMessageLimit").value(10))
                .andExpect(jsonPath("$.inquiryQuotaLifted").value(false))
                .andExpect(jsonPath("$.mediaAllowed").value(false))
                .andExpect(jsonPath("$.tripOrigin").value("Paris"))
                .andExpect(jsonPath("$.tripDestination").value("Dakar"))
                .andExpect(jsonPath("$.tripTransportMode").value("PLANE"))
                .andExpect(jsonPath("$.otherParticipant.name").exists())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(json).get("id").asText();

        mockMvc.perform(post("/conversations/inquiry").with(authentication(as(asker)))
                        .contentType(MediaType.APPLICATION_JSON).content(body(announcement.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void open_withoutVerifiedKyc_returns403KycRequiredProblem() throws Exception {
        UserEntity unverified = persistUser("Nouveau", KycStatus.PENDING);

        mockMvc.perform(post("/conversations/inquiry").with(authentication(as(unverified)))
                        .contentType(MediaType.APPLICATION_JSON).content(body(announcement.getId())))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type", containsString("kyc-required")));
    }

    @Test
    void open_unknownAnnouncement_returns404Problem() throws Exception {
        mockMvc.perform(post("/conversations/inquiry").with(authentication(as(asker)))
                        .contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void open_withoutAnnouncementId_returns4xx() throws Exception {
        mockMvc.perform(post("/conversations/inquiry").with(authentication(as(asker)))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void creatorSeesTheThreadOnlyAfterTheAskersFirstMessage() throws Exception {
        UUID id = open();

        mockMvc.perform(get("/conversations").header(CAP, "inquiry").with(authentication(as(asker))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", hasItem(id.toString())));
        mockMvc.perform(get("/conversations").header(CAP, "inquiry").with(authentication(as(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", not(hasItem(id.toString()))));

        ConversationEntity conv = conversationRepository.findById(id).orElseThrow();
        // Un message du créateur ne suffit pas : seul le premier message du demandeur ouvre le fil.
        notifyMessage(conv, owner);
        mockMvc.perform(get("/conversations").header(CAP, "inquiry").with(authentication(as(owner))))
                .andExpect(jsonPath("$.content[*].id", not(hasItem(id.toString()))));
        assertThat(conversationRepository.findById(id).orElseThrow().getLastActivityAt()).isNotNull();
        assertThat(conversationRepository.findById(id).orElseThrow().getInquiryFirstMessageAt()).isNull();

        notifyMessage(conv, asker);
        var first = conversationRepository.findById(id).orElseThrow().getInquiryFirstMessageAt();
        assertThat(first).isNotNull();
        mockMvc.perform(get("/conversations").header(CAP, "inquiry").with(authentication(as(owner))))
                .andExpect(jsonPath("$.content[*].id", hasItem(id.toString())));

        notifyMessage(conv, asker); // idempotent : la date du premier message ne bouge pas
        assertThat(conversationRepository.findById(id).orElseThrow().getInquiryFirstMessageAt()).isEqualTo(first);
    }

    @Test
    void getById_worksWithoutBid_forBothParticipants() throws Exception {
        UUID id = open();

        mockMvc.perform(get("/conversations/{id}", id).header(CAP, "inquiry").with(authentication(as(asker))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("INQUIRY"))
                .andExpect(jsonPath("$.bidId").doesNotExist())
                .andExpect(jsonPath("$.tripOrigin").value("Paris"));
        mockMvc.perform(get("/conversations/{id}", id).header(CAP, "inquiry").with(authentication(as(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inquiryMessageLimit").value(10));
    }

    @Test
    void notifyRoute_worksWithoutBid() throws Exception {
        UUID id = open();
        ConversationEntity conv = conversationRepository.findById(id).orElseThrow();

        mockMvc.perform(post("/internal/messaging/notify").header("X-Internal-Secret", "test-internal-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "conversationId", conv.getFirestoreConversationId(),
                                "senderFirebaseUid", asker.getFirebaseUid(),
                                "messagePreview", "Bonjour"))))
                .andExpect(status().isOk());
    }

    @Test
    void firestoreConversationId_isAlignedOnTheDatabaseId_afterReload() throws Exception {
        UUID id = open();

        ConversationEntity reloaded = conversationRepository.findById(id).orElseThrow();
        assertThat(reloaded.getFirestoreConversationId()).isEqualTo("conv_inq_" + id);
        assertThat(conversationRepository.findByFirestoreConversationId("conv_inq_" + id)).isPresent();
    }

    @Test
    void photos_areRefusedInAnInquiry() throws Exception {
        UUID id = open();

        mockMvc.perform(multipart("/conversations/{id}/images", id)
                        .file(new MockMultipartFile("file", "p.jpg", "image/jpeg", new byte[] {1, 2, 3}))
                        .with(authentication(as(asker))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type", containsString("media-not-allowed")));
    }

    @Test
    void withoutTheCapabilityHeader_theInquiryIsInvisibleEverywhere() throws Exception {
        UUID id = open();
        conversationRepository.markInquiryFirstMessage(id, java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));

        for (String header : new String[] {null, "other", "inquiry2"}) {
            var list = get("/conversations").with(authentication(as(owner)));
            var one = get("/conversations/{id}", id).with(authentication(as(owner)));
            var archived = get("/conversations/archived").with(authentication(as(owner)));
            var archive = post("/conversations/{id}/archive", id).with(authentication(as(owner)));
            var mute = post("/conversations/{id}/mute", id).with(authentication(as(owner)));
            if (header != null) {
                list.header(CAP, header);
                one.header(CAP, header);
                archived.header(CAP, header);
                archive.header(CAP, header);
                mute.header(CAP, header);
            }
            mockMvc.perform(list).andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[*].id", not(hasItem(id.toString()))));
            mockMvc.perform(one).andExpect(status().isForbidden());
            mockMvc.perform(archive).andExpect(status().isForbidden());
            mockMvc.perform(mute).andExpect(status().isForbidden());
            mockMvc.perform(archived).andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[*].id", not(hasItem(id.toString()))));
        }
        assertThat(conversationRepository.findById(id).orElseThrow().isArchivedByUser(owner.getId())).isFalse();

        // Avec l'activation, le même fil existe pour le créateur.
        mockMvc.perform(get("/conversations/{id}", id).header(CAP, "foo, inquiry").with(authentication(as(owner))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/conversations/{id}/archive", id).header(CAP, "inquiry").with(authentication(as(owner))))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/conversations/archived").header(CAP, "inquiry").with(authentication(as(owner))))
                .andExpect(jsonPath("$.content[*].id", hasItem(id.toString())));
    }

    @Test
    void open_requiresAnAuthenticatedUserWithARole() throws Exception {
        mockMvc.perform(post("/conversations/inquiry")
                        .contentType(MediaType.APPLICATION_JSON).content(body(announcement.getId())))
                .andExpect(status().isUnauthorized());

        var noRole = new UsernamePasswordAuthenticationToken(asker.getFirebaseUid(), null, List.of());
        mockMvc.perform(post("/conversations/inquiry").with(authentication(noRole))
                        .contentType(MediaType.APPLICATION_JSON).content(body(announcement.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void firstMessageDate_isNeverRewrittenByAnEntitySave() throws Exception {
        UUID id = open();
        ConversationEntity stale = conversationRepository.findById(id).orElseThrow();
        assertThat(stale.getInquiryFirstMessageAt()).isNull();

        conversationRepository.markInquiryFirstMessage(id, java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        stale.archiveForUser(asker.getId());
        conversationRepository.saveAndFlush(stale);

        assertThat(conversationRepository.findById(id).orElseThrow().getInquiryFirstMessageAt()).isNotNull();
    }

    @Test
    void purgedInquiry_leavesEveryList_andALateNotifyNeitherRepairsNorPushes() throws Exception {
        UUID id = open();
        conversationRepository.markInquiryFirstMessage(id, java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        java.time.LocalDateTime longAgo = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(40)
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        jdbc.update("UPDATE conversations SET last_activity_at = ?, created_at = ? WHERE id = ?", longAgo, longAgo, id);
        String firestoreId = "conv_inq_" + id;
        org.mockito.Mockito.when(firestoreService.purgeInquiryConversation(firestoreId)).thenReturn(true);

        inquiryInactivityScheduler.purgeStaleInquiries();

        assertThat(jdbc.queryForObject("SELECT deleted_at FROM conversations WHERE id = ?", Object.class, id)).isNotNull();
        for (UserEntity participant : new UserEntity[] {asker, owner}) {
            mockMvc.perform(get("/conversations").header(CAP, "inquiry").with(authentication(as(participant))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[*].id", not(hasItem(id.toString()))));
            mockMvc.perform(get("/conversations/{id}", id).header(CAP, "inquiry").with(authentication(as(participant))))
                    .andExpect(status().isForbidden());
        }

        // La Cloud Function signale un message tardif (règles : repli « parent absent ») : 200, et
        // ni le document Firestore ni la ligne ne reviennent, aucune notification ne part.
        org.mockito.Mockito.reset(firestoreService, notificationDispatcher);
        mockMvc.perform(post("/internal/messaging/notify").header("X-Internal-Secret", "test-internal-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "conversationId", firestoreId,
                                "senderFirebaseUid", asker.getFirebaseUid(),
                                "messagePreview", "Toujours là ?"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientFirebaseUid").doesNotExist());
        org.mockito.Mockito.verifyNoInteractions(firestoreService, notificationDispatcher);
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM conversations WHERE id = ?", Object.class, id)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT last_activity_at FROM conversations WHERE id = ?",
                java.time.LocalDateTime.class, id)).isEqualTo(longAgo);

        // Une nouvelle question ouvre un fil neuf : la ligne purgée ne revient pas.
        assertThat(open()).isNotEqualTo(id);
    }

    private void notifyMessage(ConversationEntity conv, UserEntity from) throws Exception {
        mockMvc.perform(post("/internal/messaging/notify").header("X-Internal-Secret", "test-internal-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "conversationId", conv.getFirestoreConversationId(),
                                "senderFirebaseUid", from.getFirebaseUid(),
                                "messagePreview", "Salut"))))
                .andExpect(status().isOk());
    }

    private static UsernamePasswordAuthenticationToken as(UserEntity user) {
        return new UsernamePasswordAuthenticationToken(user.getFirebaseUid(), null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER"), new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private UserEntity persistUser(String firstName, KycStatus kyc) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-inq-" + UUID.randomUUID());
        u.setFirstName(firstName);
        u.setLastName("Test");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(kyc);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.SENDER);
        roles.add(Role.TRAVELER);
        u.setRoles(roles);
        u.setTotalTrips(0);
        return userRepository.save(u);
    }

    private AnnouncementEntity persistAnnouncement(UserEntity traveler) {
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
}
