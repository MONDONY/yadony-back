package com.yadony.api.messaging;

import com.github.benmanes.caffeine.cache.Cache;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ConversationMediaService — envoi et lecture des photos (FLUTTER-B4)")
class ConversationMediaServiceTest {

    ConversationRepository conversationRepository = mock(ConversationRepository.class);
    ConversationService conversationService = mock(ConversationService.class);
    ConversationMediaPolicy policy = mock(ConversationMediaPolicy.class);
    MessagingImageRepository imageRepository = mock(MessagingImageRepository.class);
    StorageService storageService = mock(StorageService.class);
    FirestoreService firestoreService = mock(FirestoreService.class);
    AuditService auditService = mock(AuditService.class);
    Cache<String, AtomicInteger> userCache = new MessagingMediaConfig().messagingImageUserRateCache();
    Cache<String, AtomicInteger> convCache = new MessagingMediaConfig().messagingImageConversationRateCache();
    ConversationMediaService service;

    UserEntity sender;
    ConversationEntity conv;
    UUID bidId = UUID.randomUUID();
    MockMultipartFile file = new MockMultipartFile("file", "p.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1});

    @BeforeEach
    void setUp() throws Exception {
        service = new ConversationMediaService(conversationRepository, conversationService, policy, imageRepository,
                storageService, firestoreService, auditService, userCache, convCache, "https://api.yadony.test/");
        sender = ConversationMediaPolicyTest.user(UUID.randomUUID());
        conv = ConversationMediaPolicyTest.withId(
                new ConversationEntity(bidId, sender.getId(), UUID.randomUUID(), "conv_" + bidId));
        when(conversationRepository.findByIdAndParticipant(conv.getId(), sender.getId())).thenReturn(Optional.of(conv));
        when(conversationRepository.findByIdAndParticipantIgnoreDeleted(conv.getId(), sender.getId()))
                .thenReturn(Optional.of(conv));
        when(storageService.storeMessagingImage(anyString(), anyString(), any(), any()))
                .thenAnswer(inv -> new StorageService.StoredImage(
                        inv.getArgument(0) + inv.getArgument(1).toString() + "_full.jpg",
                        inv.getArgument(0) + inv.getArgument(1).toString() + "_thumb.jpg"));
        when(imageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void sendImage_storesTracesWritesFirestore_andUpdatesPreview() {
        String messageId = service.sendImage(conv.getId(), sender, file, "  Reply42 ");

        assertThat(messageId).matches("[A-Za-z0-9]{20}");
        String full = "messaging/" + conv.getFirestoreConversationId() + "/" + messageId + "_full.jpg";
        String thumb = "messaging/" + conv.getFirestoreConversationId() + "/" + messageId + "_thumb.jpg";
        verify(storageService).validateImageUpload(file);
        verify(storageService).storeMessagingImage(eq("messaging/" + conv.getFirestoreConversationId() + "/"),
                eq(messageId), any(), eq("image/jpeg"));

        ArgumentCaptor<MessagingImageEntity> row = ArgumentCaptor.forClass(MessagingImageEntity.class);
        verify(imageRepository).save(row.capture());
        assertThat(row.getValue().getConversationId()).isEqualTo(conv.getId());
        assertThat(row.getValue().getBidId()).isEqualTo(bidId);
        assertThat(row.getValue().getFirestoreMessageId()).isEqualTo(messageId);
        assertThat(row.getValue().getSenderId()).isEqualTo(sender.getId());
        assertThat(row.getValue().getImageKey()).isEqualTo(full);
        assertThat(row.getValue().getThumbKey()).isEqualTo(thumb);

        verify(firestoreService).addImageMessage(conv.getFirestoreConversationId(), messageId,
                sender.getFirebaseUid(), full, thumb,
                "https://api.yadony.test/api/v1/conversations/" + conv.getId() + "/messages/" + messageId + "/image",
                "Reply42");
        verify(conversationService).updateLastMessage(conv.getFirestoreConversationId(), "📷 Photo");
        verify(auditService).log(eq("conversation"), eq(conv.getId()), eq("MESSAGE_IMAGE_SENT"), eq(sender.getId()), any());
    }

    @Test
    void sendImage_checksParticipantBlockAndPolicy_beforeTouchingTheFile() {
        when(conversationRepository.findByIdAndParticipant(conv.getId(), sender.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.sendImage(conv.getId(), sender, file, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        when(conversationRepository.findByIdAndParticipant(conv.getId(), sender.getId())).thenReturn(Optional.of(conv));
        doThrow(new YadonyBusinessException(HttpStatus.FORBIDDEN, "media-not-allowed", "t", "d"))
                .when(policy).assertAllowed(conv, sender);
        assertThatThrownBy(() -> service.sendImage(conv.getId(), sender, file, null))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("media-not-allowed"));
        verify(conversationService, org.mockito.Mockito.atLeastOnce()).assertMessagingAllowed(conv, sender.getId());
        verify(storageService, never()).validateImageUpload(any());
        verify(firestoreService, never()).addImageMessage(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void sendImage_rejectsInvalidReplyTo_with422() {
        assertThatThrownBy(() -> service.sendImage(conv.getId(), sender, file, "bad id!"))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo("invalid-reply-to");
                });
        assertThatThrownBy(() -> service.sendImage(conv.getId(), sender, file, "a".repeat(41)))
                .isInstanceOf(YadonyBusinessException.class);
        // Blanc = pas de citation.
        service.sendImage(conv.getId(), sender, file, "   ");
        verify(firestoreService).addImageMessage(any(), any(), any(), any(), any(), any(), isNull());
    }

    @Test
    void sendImage_limitsTenPerUser_thenReturns429() {
        for (int i = 0; i < ConversationMediaService.USER_LIMIT; i++) {
            service.sendImage(conv.getId(), sender, file, null);
        }
        assertThatThrownBy(() -> service.sendImage(conv.getId(), sender, file, null))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(e.getErrorCode()).isEqualTo("media-rate-limited");
                });
        // Le refus ne consomme pas de quota.
        assertThat(userCache.getIfPresent(sender.getId().toString()).get()).isEqualTo(ConversationMediaService.USER_LIMIT);
    }

    @Test
    void sendImage_limitsFiftyPerConversationPerDay() {
        convCache.put(conv.getId() + ":" + sender.getId(),
                new AtomicInteger(ConversationMediaService.CONVERSATION_DAILY_LIMIT));
        assertThatThrownBy(() -> service.sendImage(conv.getId(), sender, file, null))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        assertThat(userCache.getIfPresent(sender.getId().toString()).get()).isZero();
    }

    @Test
    void sendImage_firestoreFailure_cleansUp_andReturns503() {
        doThrow(new RuntimeException("firestore down")).when(firestoreService)
                .addImageMessage(any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.sendImage(conv.getId(), sender, file, null))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getErrorCode()).isEqualTo("media-send-failed");
                });

        ArgumentCaptor<MessagingImageEntity> row = ArgumentCaptor.forClass(MessagingImageEntity.class);
        verify(imageRepository, org.mockito.Mockito.times(2)).save(row.capture());
        assertThat(row.getValue().getDeletedAt()).isNotNull();
        verify(storageService).deleteQuietly(row.getValue().getImageKey());
        verify(storageService).deleteQuietly(row.getValue().getThumbKey());
        verify(conversationService, never()).updateLastMessage(any(), any());
        assertThat(userCache.getIfPresent(sender.getId().toString()).get()).isZero();
    }

    @Test
    void sendImage_processingError_isPropagated_andQuotaRefunded() {
        when(storageService.storeMessagingImage(anyString(), anyString(), any(), any()))
                .thenThrow(new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "image/invalid", "t", "d"));
        assertThatThrownBy(() -> service.sendImage(conv.getId(), sender, file, null))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("image/invalid"));
        verify(imageRepository, never()).save(any());
        assertThat(userCache.getIfPresent(sender.getId().toString()).get()).isZero();
    }

    @Test
    void sendImage_unreadableUpload_returns503() throws Exception {
        MockMultipartFile broken = new MockMultipartFile("file", "p.jpg", "image/jpeg", new byte[]{1}) {
            @Override
            public byte[] getBytes() throws IOException {
                throw new IOException("stream closed");
            }
        };
        assertThatThrownBy(() -> service.sendImage(conv.getId(), sender, broken, null))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    // ── Lecture ──────────────────────────────────────────────────────────────

    private MessagingImageEntity image(String messageId) {
        return new MessagingImageEntity(conv.getId(), bidId, messageId, sender.getId(), "k_full", "k_thumb");
    }

    @Test
    void loadImage_returnsRequestedVariant() {
        when(imageRepository.findByConversationIdAndFirestoreMessageId(conv.getId(), "Msg1"))
                .thenReturn(Optional.of(image("Msg1")));
        when(firestoreService.findMessage(conv.getFirestoreConversationId(), "Msg1"))
                .thenReturn(Optional.of(new FirestoreService.MessageSnapshot("uid", false)));
        when(storageService.downloadBytes("k_thumb")).thenReturn(Optional.of(new byte[]{1}));
        when(storageService.downloadBytes("k_full")).thenReturn(Optional.of(new byte[]{2}));

        assertThat(service.loadImage(conv.getId(), sender.getId(), "Msg1", ConversationMediaService.Variant.THUMB))
                .containsExactly(1);
        assertThat(service.loadImage(conv.getId(), sender.getId(), "Msg1", ConversationMediaService.Variant.FULL))
                .containsExactly(2);
        verify(conversationService, org.mockito.Mockito.times(2)).assertMessagingAllowed(conv, sender.getId());
    }

    @Test
    void loadImage_unknownOrMalformedMessage_is404() {
        when(imageRepository.findByConversationIdAndFirestoreMessageId(any(), any())).thenReturn(Optional.empty());
        for (String id : new String[]{"Nope", "../etc", null}) {
            assertThatThrownBy(() -> service.loadImage(conv.getId(), sender.getId(), id,
                    ConversationMediaService.Variant.FULL))
                    .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(e.getErrorCode()).isEqualTo("image-not-found");
                    });
        }
    }

    @Test
    void loadImage_purgedDeletedOrMissingObject_is410() {
        MessagingImageEntity purged = image("Purged");
        purged.markPurged(java.time.LocalDateTime.now());
        when(imageRepository.findByConversationIdAndFirestoreMessageId(conv.getId(), "Purged"))
                .thenReturn(Optional.of(purged));
        when(imageRepository.findByConversationIdAndFirestoreMessageId(conv.getId(), "Deleted"))
                .thenReturn(Optional.of(image("Deleted")));
        when(firestoreService.findMessage(conv.getFirestoreConversationId(), "Deleted"))
                .thenReturn(Optional.of(new FirestoreService.MessageSnapshot("uid", true)));
        when(imageRepository.findByConversationIdAndFirestoreMessageId(conv.getId(), "Gone"))
                .thenReturn(Optional.of(image("Gone")));
        when(firestoreService.findMessage(conv.getFirestoreConversationId(), "Gone")).thenReturn(Optional.empty());
        when(storageService.downloadBytes("k_full")).thenReturn(Optional.empty());

        for (String id : new String[]{"Purged", "Deleted", "Gone"}) {
            assertThatThrownBy(() -> service.loadImage(conv.getId(), sender.getId(), id,
                    ConversationMediaService.Variant.FULL))
                    .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.GONE);
                        assertThat(e.getErrorCode()).isEqualTo("image-unavailable");
                    });
        }
    }

    @Test
    void loadImage_nonParticipant_is403() {
        UUID stranger = UUID.randomUUID();
        when(conversationRepository.findByIdAndParticipantIgnoreDeleted(conv.getId(), stranger))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.loadImage(conv.getId(), stranger, "Msg1",
                ConversationMediaService.Variant.THUMB))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void imageUrl_handlesBaseUrlWithoutTrailingSlash() {
        ConversationMediaService s = new ConversationMediaService(conversationRepository, conversationService, policy,
                imageRepository, storageService, firestoreService, auditService, userCache, convCache,
                "http://localhost:8080");
        UUID id = UUID.randomUUID();
        assertThat(s.imageUrl(id, "M1")).isEqualTo("http://localhost:8080/api/v1/conversations/" + id + "/messages/M1/image");
    }
}
