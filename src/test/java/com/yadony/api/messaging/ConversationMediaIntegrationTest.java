package com.yadony.api.messaging;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.StorageService;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Photos de messagerie de bout en bout (FLUTTER-B4) : envoi 201/403/404/422/429, lecture
 * 200 + Cache-Control / 403 / 404 / 410, {@code mediaAllowed} dans la réponse conversation,
 * conversation destinataire ouverte à un compte sans rôle SENDER/TRAVELER.
 *
 * <p>R2 et Firestore sont simulés : StorageService est espionné (validation réelle des octets
 * d'en-tête, stockage et lecture simulés), FirestoreService est un mock.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ConversationMediaIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired ConversationRepository conversationRepository;
    @Autowired MessagingImageRepository imageRepository;
    @MockitoSpyBean StorageService storageService;
    @MockitoBean FirestoreService firestoreService;

    UserEntity sender;
    UserEntity traveler;
    BidEntity bid;
    ConversationEntity conversation;
    static byte[] jpeg;

    @BeforeEach
    void seed() throws Exception {
        sender = persistUser("Awa", true);
        traveler = persistUser("Moussa", true);
        bid = persistBid(BidStatus.ACCEPTED, PaymentMethod.STRIPE);
        conversation = conversationRepository.saveAndFlush(
                new ConversationEntity(bid.getId(), sender.getId(), traveler.getId(), "conv_media_" + bid.getId()));
        if (jpeg == null) {
            BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "jpg", out);
            jpeg = out.toByteArray();
        }
        doAnswer(inv -> new StorageService.StoredImage(
                inv.getArgument(0) + inv.getArgument(1).toString() + "_full.jpg",
                inv.getArgument(0) + inv.getArgument(1).toString() + "_thumb.jpg"))
                .when(storageService).storeMessagingImage(anyString(), anyString(), any(), any());
    }

    private MockMultipartHttpServletRequestBuilder upload(UUID conversationId, byte[] bytes, String contentType) {
        return multipart("/conversations/{id}/images", conversationId)
                .file(new MockMultipartFile("file", "photo.jpg", contentType, bytes));
    }

    @Test
    void send_returns201_tracesThePhoto_andWritesTheFirestoreMessage() throws Exception {
        String body = mockMvc.perform(upload(conversation.getId(), jpeg, "image/jpeg").param("replyToId", "Prev1")
                        .with(authentication(as(sender))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.messageId").isString())
                .andReturn().getResponse().getContentAsString();
        String messageId = body.replaceAll(".*\"messageId\":\"([A-Za-z0-9]+)\".*", "$1");

        MessagingImageEntity row = imageRepository
                .findByConversationIdAndFirestoreMessageId(conversation.getId(), messageId).orElseThrow();
        assertThat(row.getBidId()).isEqualTo(bid.getId());
        verify(firestoreService).addImageMessage(eq(conversation.getFirestoreConversationId()), eq(messageId),
                eq(sender.getFirebaseUid()), eq(row.getImageKey()), eq(row.getThumbKey()),
                eq("http://localhost:8080/api/v1/conversations/" + conversation.getId() + "/messages/" + messageId + "/image"),
                eq("Prev1"));
        verify(firestoreService).updateLastMessage(eq(conversation.getFirestoreConversationId()),
                eq("📷 Photo"), anyString());
    }

    @Test
    void conversationResponse_exposesMediaAllowed() throws Exception {
        mockMvc.perform(get("/conversations/{id}", conversation.getId()).with(authentication(as(sender))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mediaAllowed").value(true));

        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        bidRepository.saveAndFlush(bid);
        mockMvc.perform(get("/conversations/{id}", conversation.getId()).with(authentication(as(sender))))
                .andExpect(jsonPath("$.mediaAllowed").value(false));
    }

    @Test
    void send_awaitingMobileMoneyPayment_returns403MediaNotAllowed() throws Exception {
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        bid.setPaymentMethod(PaymentMethod.MOBILE_MONEY);
        bidRepository.saveAndFlush(bid);

        mockMvc.perform(upload(conversation.getId(), jpeg, "image/jpeg").with(authentication(as(sender))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("media-not-allowed"));
        verify(firestoreService, never()).addImageMessage(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void send_byAStranger_returns403() throws Exception {
        UserEntity stranger = persistUser("Tiers", true);
        mockMvc.perform(upload(conversation.getId(), jpeg, "image/jpeg").with(authentication(as(stranger))))
                .andExpect(status().isForbidden());
    }

    @Test
    void send_spoofedImage_returns422() throws Exception {
        mockMvc.perform(upload(conversation.getId(), "%PDF-1.7 hello".getBytes(StandardCharsets.UTF_8), "image/jpeg")
                        .with(authentication(as(sender))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_FILE_TYPE"));
        mockMvc.perform(upload(conversation.getId(), jpeg, "application/pdf").with(authentication(as(sender))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void send_invalidReplyTo_returns422() throws Exception {
        mockMvc.perform(upload(conversation.getId(), jpeg, "image/jpeg").param("replyToId", "../x")
                        .with(authentication(as(sender))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("invalid-reply-to"));
    }

    @Test
    void send_eleventhPhotoInTenMinutes_returns429() throws Exception {
        for (int i = 0; i < ConversationMediaService.USER_LIMIT; i++) {
            mockMvc.perform(upload(conversation.getId(), jpeg, "image/jpeg").with(authentication(as(traveler))))
                    .andExpect(status().isCreated());
        }
        mockMvc.perform(upload(conversation.getId(), jpeg, "image/jpeg").with(authentication(as(traveler))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("media-rate-limited"));
    }

    @Test
    void recipientConversation_isOpenToARoleLessRecipient() throws Exception {
        UserEntity recipient = persistUser("Fatou", false);
        ConversationEntity rc = conversationRepository.saveAndFlush(ConversationEntity.forRecipient(
                bid.getId(), recipient.getId(), traveler.getId(), "conv_media_r_" + bid.getId()));

        mockMvc.perform(upload(rc.getId(), jpeg, "image/jpeg").with(authentication(asRoleLess(recipient))))
                .andExpect(status().isCreated());
    }

    // ── Lecture ──────────────────────────────────────────────────────────────

    private MessagingImageEntity seedImage(String messageId) {
        return imageRepository.saveAndFlush(new MessagingImageEntity(conversation.getId(), bid.getId(), messageId,
                sender.getId(), "messaging/x/" + messageId + "_full.jpg", "messaging/x/" + messageId + "_thumb.jpg"));
    }

    @Test
    void read_returnsBytes_withImmutablePrivateCache() throws Exception {
        seedImage("ReadMe1");
        doReturn(Optional.of(new byte[]{1, 2, 3})).when(storageService).downloadBytes("messaging/x/ReadMe1_thumb.jpg");
        doReturn(Optional.of(new byte[]{4, 5})).when(storageService).downloadBytes("messaging/x/ReadMe1_full.jpg");
        when(firestoreService.findMessage(any(), eq("ReadMe1")))
                .thenReturn(Optional.of(new FirestoreService.MessageSnapshot("uid", false)));

        mockMvc.perform(get("/conversations/{id}/messages/{m}/image", conversation.getId(), "ReadMe1")
                        .param("variant", "thumb").with(authentication(as(traveler))))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Cache-Control", "max-age=31536000, private, immutable"))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
        mockMvc.perform(get("/conversations/{id}/messages/{m}/image", conversation.getId(), "ReadMe1")
                        .with(authentication(as(sender))))
                .andExpect(status().isOk())
                .andExpect(content().bytes(new byte[]{4, 5}));
    }

    @Test
    void read_purgedOrDeleted_returns410_unknown404_badVariant400_stranger403() throws Exception {
        MessagingImageEntity purged = seedImage("Purged1");
        purged.markPurged(LocalDateTime.now());
        imageRepository.saveAndFlush(purged);
        seedImage("Deleted1");
        when(firestoreService.findMessage(any(), eq("Deleted1")))
                .thenReturn(Optional.of(new FirestoreService.MessageSnapshot("uid", true)));

        mockMvc.perform(get("/conversations/{id}/messages/{m}/image", conversation.getId(), "Purged1")
                        .with(authentication(as(sender))))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("image-unavailable"));
        mockMvc.perform(get("/conversations/{id}/messages/{m}/image", conversation.getId(), "Deleted1")
                        .with(authentication(as(sender))))
                .andExpect(status().isGone());
        mockMvc.perform(get("/conversations/{id}/messages/{m}/image", conversation.getId(), "Unknown1")
                        .with(authentication(as(sender))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("image-not-found"));
        mockMvc.perform(get("/conversations/{id}/messages/{m}/image", conversation.getId(), "Purged1")
                        .param("variant", "huge").with(authentication(as(sender))))
                .andExpect(status().isBadRequest());
        UserEntity stranger = persistUser("Tiers", true);
        mockMvc.perform(get("/conversations/{id}/messages/{m}/image", conversation.getId(), "Purged1")
                        .with(authentication(as(stranger))))
                .andExpect(status().isForbidden());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static UsernamePasswordAuthenticationToken as(UserEntity user) {
        return new UsernamePasswordAuthenticationToken(user.getFirebaseUid(), null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER"), new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private static UsernamePasswordAuthenticationToken asRoleLess(UserEntity user) {
        return new UsernamePasswordAuthenticationToken(user.getFirebaseUid(), null, List.<GrantedAuthority>of());
    }

    private UserEntity persistUser(String firstName, boolean withRoles) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-media-" + UUID.randomUUID());
        u.setFirstName(firstName);
        u.setLastName("Test");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.PENDING);
        Set<Role> roles = new HashSet<>();
        if (withRoles) {
            roles.add(Role.SENDER);
            roles.add(Role.TRAVELER);
        }
        u.setRoles(roles);
        u.setTotalTrips(0);
        return userRepository.save(u);
    }

    private BidEntity persistBid(BidStatus status, PaymentMethod method) {
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
        a = announcementRepository.save(a);

        BidEntity b = new BidEntity();
        b.setAnnouncementId(a.getId());
        b.setSenderId(sender.getId());
        b.setWeightKg(new BigDecimal("3.00"));
        b.setStatus(status);
        b.setPaymentMethod(method);
        b.setRecipientName("Fatou Diop");
        b.setRecipientPhone("+221 77 123 45 67");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        b.setTrackingNumber("DON-" + suffix);
        b.setTrackingToken(UUID.randomUUID().toString());
        b.setConfirmationCode("123456");
        return bidRepository.saveAndFlush(b);
    }
}
