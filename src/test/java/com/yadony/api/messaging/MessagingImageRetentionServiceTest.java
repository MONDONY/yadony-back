package com.yadony.api.messaging;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.MessagingMediaRetentionHold;
import com.yadony.api.common.StorageService;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("MessagingImageRetentionService — conservation des photos (FLUTTER-B4)")
class MessagingImageRetentionServiceTest {

    static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    static final LocalDateTime NOW_LDT = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    MessagingImageRepository imageRepository = mock(MessagingImageRepository.class);
    ConversationRepository conversationRepository = mock(ConversationRepository.class);
    BidRepository bidRepository = mock(BidRepository.class);
    StorageService storageService = mock(StorageService.class);
    FirestoreService firestoreService = mock(FirestoreService.class);
    AuditService auditService = mock(AuditService.class);
    MessagingMediaRetentionHold disputeHold = mock(MessagingMediaRetentionHold.class);
    MessagingMediaRetentionHold reportHold = mock(MessagingMediaRetentionHold.class);
    MessagingImageRetentionService service;

    UUID bidId = UUID.randomUUID();
    ConversationEntity conv;
    MessagingImageEntity img;

    @BeforeEach
    void setUp() throws Exception {
        service = new MessagingImageRetentionService(imageRepository, conversationRepository, bidRepository,
                storageService, firestoreService, auditService, List.of(disputeHold, reportHold),
                Clock.fixed(NOW, ZoneOffset.UTC));
        conv = ConversationMediaPolicyTest.withId(
                new ConversationEntity(bidId, UUID.randomUUID(), UUID.randomUUID(), "conv_" + bidId));
        img = new MessagingImageEntity(conv.getId(), bidId, "Msg1", UUID.randomUUID(),
                "messaging/conv_x/Msg1_full.jpg", "messaging/conv_x/Msg1_thumb.jpg");
        when(imageRepository.findBidIdsWithLiveImages()).thenReturn(List.of(bidId));
        when(imageRepository.findLiveByBidId(bidId)).thenReturn(List.of(img));
        when(conversationRepository.findAllByBidId(bidId)).thenReturn(List.of(conv));
        when(conversationRepository.findAllById(any())).thenReturn(List.of(conv));
    }

    static BidEntity bid(BidStatus status, LocalDateTime updatedAt, LocalDateTime deliveredAt) throws Exception {
        BidEntity b = new BidEntity();
        b.setStatus(status);
        Field f = com.yadony.api.common.BaseEntity.class.getDeclaredField("updatedAt");
        f.setAccessible(true);
        f.set(b, updatedAt);
        if (deliveredAt != null) b.markDelivered(deliveredAt);
        return b;
    }

    @ParameterizedTest(name = "{0} : jamais purgé par l'échéance")
    @EnumSource(value = BidStatus.class, names = {"AWAITING_PAYMENT", "PENDING", "PAYMENT_ESCROWED", "NEGOTIATING",
            "ACCEPTED", "HANDED_OVER", "IN_TRANSIT", "ARRIVED"})
    void runningBid_hasNoRetentionEnd(BidStatus status) throws Exception {
        assertThat(MessagingImageRetentionService.retentionEnd(bid(status, NOW_LDT.minusYears(1), null))).isEmpty();
    }

    @Test
    void retentionEnd_isDeliveryPlus7_orEndOfBidPlus7() throws Exception {
        assertThat(MessagingImageRetentionService.retentionEnd(
                bid(BidStatus.COMPLETED, NOW_LDT, NOW_LDT.minusDays(2)))).contains(NOW_LDT.plusDays(5));
        // Livré sans date : repli sur la dernière mise à jour.
        assertThat(MessagingImageRetentionService.retentionEnd(
                bid(BidStatus.COMPLETED, NOW_LDT.minusDays(1), null))).contains(NOW_LDT.plusDays(6));
        for (BidStatus s : List.of(BidStatus.CANCELLED, BidStatus.NO_SHOW, BidStatus.PARCEL_REFUSED,
                BidStatus.REJECTED, BidStatus.EXPIRED, BidStatus.NEGOTIATION_CLOSED)) {
            assertThat(MessagingImageRetentionService.retentionEnd(bid(s, NOW_LDT.minusDays(3), null)))
                    .contains(NOW_LDT.plusDays(4));
        }
        assertThat(MessagingImageRetentionService.retentionEnd(bid(null, NOW_LDT, null))).isEmpty();
        assertThat(MessagingImageRetentionService.retentionEnd(bid(BidStatus.CANCELLED, null, null))).isEmpty();
    }

    @Test
    void purgeExpired_deletesObjects_marksRow_flagsFirestore_andAudits() throws Exception {
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(
                bid(BidStatus.COMPLETED, NOW_LDT.minusDays(8), NOW_LDT.minusDays(7))));

        assertThat(service.purgeExpired()).isEqualTo(1);

        verify(storageService).deleteFile("messaging/conv_x/Msg1_full.jpg");
        verify(storageService).deleteFile("messaging/conv_x/Msg1_thumb.jpg");
        assertThat(img.getPurgedAt()).isEqualTo(NOW_LDT);
        verify(imageRepository).save(img);
        verify(firestoreService).markImageExpired(conv.getFirestoreConversationId(), "Msg1");
        verify(auditService).log(eq("bid"), eq(bidId), eq("MESSAGING_IMAGES_PURGED"), eq(null), any());
    }

    @Test
    void purgeExpired_keepsPhotos_beforeTheDeadline() throws Exception {
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(
                bid(BidStatus.COMPLETED, NOW_LDT, NOW_LDT.minusDays(6))));
        assertThat(service.purgeExpired()).isZero();
        verify(storageService, never()).deleteFile(anyString());
    }

    @Test
    void purgeExpired_keepsPhotos_ofARunningBid() throws Exception {
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid(BidStatus.IN_TRANSIT, NOW_LDT.minusDays(60), null)));
        assertThat(service.purgeExpired()).isZero();
        verify(imageRepository, never()).findLiveByBidId(any());
    }

    @Test
    void purgeExpired_keepsPhotos_whileADisputeOrReportIsOpen() throws Exception {
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(
                bid(BidStatus.CANCELLED, NOW_LDT.minusDays(30), null)));
        when(disputeHold.holds(eq(bidId), any())).thenReturn(true);
        assertThat(service.purgeExpired()).isZero();

        when(disputeHold.holds(eq(bidId), any())).thenReturn(false);
        when(reportHold.holds(eq(bidId), any())).thenReturn(true);
        assertThat(service.purgeExpired()).isZero();
        verify(storageService, never()).deleteFile(anyString());

        // Procédure close : la purge passe.
        when(reportHold.holds(eq(bidId), any())).thenReturn(false);
        assertThat(service.purgeExpired()).isEqualTo(1);
    }

    @Test
    void purgeExpired_purgesPhotos_ofAVanishedBid() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());
        assertThat(service.purgeExpired()).isEqualTo(1);
    }

    @Test
    void purgeExpired_leavesRowIntact_whenR2Fails_soTheNextRunRetries() throws Exception {
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(
                bid(BidStatus.COMPLETED, NOW_LDT.minusDays(30), NOW_LDT.minusDays(30))));
        doThrow(new RuntimeException("R2 down")).when(storageService).deleteFile(anyString());
        assertThat(service.purgeExpired()).isZero();
        assertThat(img.isPurged()).isFalse();
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void purgeExpired_skipsPhotosWithoutConversation_forFirestoreOnly() throws Exception {
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(
                bid(BidStatus.COMPLETED, NOW_LDT.minusDays(30), NOW_LDT.minusDays(30))));
        when(conversationRepository.findAllById(any())).thenReturn(List.of());
        assertThat(service.purgeExpired()).isEqualTo(1);
        verify(firestoreService, never()).markImageExpired(any(), any());
    }

    @Test
    void purgeExpired_survivesAFailingBid() {
        when(bidRepository.findById(bidId)).thenThrow(new RuntimeException("db"));
        assertThat(service.purgeExpired()).isZero();
    }

    @Test
    void purgeBidIfDue_noLiveImages_isNoop() {
        when(bidRepository.findById(bidId)).thenReturn(Optional.empty());
        when(imageRepository.findLiveByBidId(bidId)).thenReturn(List.of());
        assertThat(service.purgeBidIfDue(bidId)).isZero();
    }

    // ── Les deux parties ont supprimé la conversation ──────────────────────────

    @Test
    void purgeConversation_deletesPrefix_andMarksRows() {
        MessagingImageEntity already = new MessagingImageEntity(conv.getId(), bidId, "Old", UUID.randomUUID(), "a", "b");
        already.markPurged(NOW_LDT.minusDays(1));
        when(imageRepository.findByConversationId(conv.getId())).thenReturn(List.of(img, already));

        assertThat(service.purgeConversation(conv, conv.getTravelerId())).isTrue();

        verify(storageService).deleteByPrefix("messaging/" + conv.getFirestoreConversationId() + "/");
        assertThat(img.getPurgedAt()).isEqualTo(NOW_LDT);
        assertThat(already.getPurgedAt()).isEqualTo(NOW_LDT.minusDays(1));
        verify(auditService).log(eq("conversation"), eq(conv.getId()), eq("MESSAGING_IMAGES_PURGED"),
                eq(conv.getTravelerId()), any());
    }

    @Test
    void purgeConversation_isHeld_byAnOpenProcedure() {
        when(reportHold.holds(eq(bidId), any())).thenReturn(true);
        assertThat(service.purgeConversation(conv, conv.getTravelerId())).isFalse();
        verify(storageService, never()).deleteByPrefix(anyString());
    }

    @Test
    void purgeConversation_returnsFalse_whenR2Fails() {
        doThrow(new RuntimeException("R2 down")).when(storageService).deleteByPrefix(anyString());
        assertThat(service.purgeConversation(conv, conv.getTravelerId())).isFalse();
        verify(imageRepository, never()).save(any());
    }

    @Test
    void purgeConversation_withoutPhotos_doesNotAudit() {
        when(imageRepository.findByConversationId(conv.getId())).thenReturn(List.of());
        assertThat(service.purgeConversation(conv, conv.getTravelerId())).isTrue();
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void scheduler_delegatesToTheService() {
        MessagingImageRetentionService retention = mock(MessagingImageRetentionService.class);
        new MessagingImagePurgeScheduler(retention).purgeExpiredImages();
        verify(retention).purgeExpired();
    }
}
