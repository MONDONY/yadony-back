package com.yadony.api.messaging;

import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.WriteResult;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.MessagingMediaRetentionHold;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InquiryInactivitySchedulerTest {

    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");

    @Mock ConversationRepository conversationRepository;
    @Mock FirestoreService firestoreService;
    @Mock AuditService auditService;
    @Mock MessagingMediaRetentionHold hold;

    private InquiryInactivityScheduler scheduler;

    private static final LocalDateTime NOW_LDT = LocalDateTime.of(2026, 11, 13, 12, 0);
    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 10, 14, 12, 0);

    @BeforeEach
    void setUp() {
        scheduler = new InquiryInactivityScheduler(conversationRepository, firestoreService, auditService,
                List.of(hold), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ConversationEntity inquiry(String firestoreId) {
        ConversationEntity c = ConversationEntity.forInquiry(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), firestoreId);
        org.springframework.test.util.ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        return c;
    }

    private void stale(ConversationEntity... convs) {
        when(conversationRepository.findStaleInquiries(CUTOFF)).thenReturn(List.of(convs));
    }

    private void reserved(ConversationEntity c, int rows) {
        when(conversationRepository.reserveStaleInquiry(c.getId(), NOW_LDT, CUTOFF)).thenReturn(rows);
    }

    @Test
    void reservesFirst_thenPurgesFirestore_thenAudits_withoutRewritingTheEntity() {
        ConversationEntity conv = inquiry("conv_stale");
        stale(conv);
        reserved(conv, 1);
        when(firestoreService.purgeInquiryConversation("conv_stale")).thenReturn(true);

        assertThat(scheduler.purgeStaleInquiries()).isEqualTo(1);

        InOrder order = inOrder(conversationRepository, firestoreService);
        order.verify(conversationRepository).reserveStaleInquiry(conv.getId(), NOW_LDT, CUTOFF);
        order.verify(firestoreService).purgeInquiryConversation("conv_stale");
        verify(conversationRepository, never()).save(any());
        verify(conversationRepository, never()).releaseInquiryReservation(any(), any());
        verify(auditService).log(eq("conversation"), eq(conv.getId()), eq("INQUIRY_PURGED"), isNull(), anyMap());
    }

    @Test
    void promotedMeanwhile_guardedUpdateReturnsZero_noFirestoreCallAtAll() {
        ConversationEntity conv = inquiry("conv_race");
        stale(conv);
        reserved(conv, 0);

        assertThat(scheduler.purgeStaleInquiries()).isZero();

        verify(firestoreService, never()).purgeInquiryConversation(any());
        verify(conversationRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), anyMap());
    }

    @Test
    void firestoreFailure_undeletesTheReservation_soTheNextRunRetries() {
        ConversationEntity conv = inquiry("conv_fs_fail");
        stale(conv);
        reserved(conv, 1);
        when(firestoreService.purgeInquiryConversation("conv_fs_fail")).thenReturn(false);

        assertThat(scheduler.purgeStaleInquiries()).isZero();

        verify(conversationRepository).releaseInquiryReservation(conv.getId(), NOW_LDT);
        verify(auditService, never()).log(any(), any(), any(), any(), anyMap());
    }

    @Test
    void failedUndelete_isLogged_andDoesNotStopTheOthers() {
        ConversationEntity broken = inquiry("conv_undelete_fail");
        ConversationEntity ok = inquiry("conv_ok");
        stale(broken, ok);
        reserved(broken, 1);
        reserved(ok, 1);
        when(firestoreService.purgeInquiryConversation("conv_undelete_fail")).thenReturn(false);
        when(firestoreService.purgeInquiryConversation("conv_ok")).thenReturn(true);
        when(conversationRepository.releaseInquiryReservation(broken.getId(), NOW_LDT))
                .thenThrow(new IllegalStateException("unique index"));

        assertThat(scheduler.purgeStaleInquiries()).isEqualTo(1);
    }

    @Test
    void heldConversation_isSkipped_noUpdateNoFirestore() {
        ConversationEntity conv = inquiry("conv_held");
        stale(conv);
        when(hold.holds(isNull(), anyCollection())).thenReturn(true);

        assertThat(scheduler.purgeStaleInquiries()).isZero();

        verify(conversationRepository, never()).reserveStaleInquiry(any(), any(), any());
        verify(firestoreService, never()).purgeInquiryConversation(any());
    }

    @Test
    void recentFirestoreMessage_meansActive_skipsAndRefreshesLastActivity() {
        ConversationEntity conv = inquiry("conv_active");
        stale(conv);
        when(firestoreService.lastMessageInstant("conv_active"))
                .thenReturn(java.util.Optional.of(Instant.parse("2026-11-10T08:00:00Z")));

        assertThat(scheduler.purgeStaleInquiries()).isZero();

        verify(conversationRepository).markInquiryActivity(conv.getId(), LocalDateTime.of(2026, 11, 10, 8, 0));
        verify(conversationRepository, never()).reserveStaleInquiry(any(), any(), any());
        verify(firestoreService, never()).purgeInquiryConversation(any());
    }

    @Test
    void oldFirestoreMessage_doesNotBlockThePurge() {
        ConversationEntity conv = inquiry("conv_old_msg");
        stale(conv);
        when(firestoreService.lastMessageInstant("conv_old_msg"))
                .thenReturn(java.util.Optional.of(Instant.parse("2026-09-01T08:00:00Z")));
        reserved(conv, 1);
        when(firestoreService.purgeInquiryConversation("conv_old_msg")).thenReturn(true);

        assertThat(scheduler.purgeStaleInquiries()).isEqualTo(1);
        verify(conversationRepository, never()).markInquiryActivity(any(), any());
    }

    @Test
    void firestoreReadFailure_skipsTheConversation_withoutPurging() {
        ConversationEntity conv = inquiry("conv_read_fail");
        stale(conv);
        when(firestoreService.lastMessageInstant("conv_read_fail")).thenThrow(new RuntimeException("down"));

        assertThat(scheduler.purgeStaleInquiries()).isZero();

        verify(conversationRepository, never()).reserveStaleInquiry(any(), any(), any());
    }

    @Test
    void missingFirestoreId_goesStraightToTheLogicalDeletion() {
        ConversationEntity conv = inquiry(null);
        stale(conv);
        reserved(conv, 1);

        assertThat(scheduler.purgeStaleInquiries()).isEqualTo(1);

        verify(firestoreService, never()).lastMessageInstant(any());
        verify(firestoreService, never()).purgeInquiryConversation(any());
        verify(auditService).log(eq("conversation"), eq(conv.getId()), eq("INQUIRY_PURGED"), isNull(), anyMap());
    }

    @Test
    void secondRun_isANoOp_whenNothingIsSelectedAnymore() {
        ConversationEntity conv = inquiry("conv_once");
        when(conversationRepository.findStaleInquiries(any())).thenReturn(List.of(conv)).thenReturn(List.of());
        reserved(conv, 1);
        when(firestoreService.purgeInquiryConversation("conv_once")).thenReturn(true);

        assertThat(scheduler.purgeStaleInquiries()).isEqualTo(1);
        assertThat(scheduler.purgeStaleInquiries()).isZero();

        verify(firestoreService).purgeInquiryConversation("conv_once");
    }

    @Test
    void oneFailingConversation_doesNotStopTheOthers() {
        ConversationEntity broken = inquiry("conv_broken");
        ConversationEntity ok = inquiry("conv_ok");
        stale(broken, ok);
        reserved(broken, 1);
        reserved(ok, 1);
        when(firestoreService.purgeInquiryConversation("conv_broken")).thenThrow(new IllegalStateException("boom"));
        when(firestoreService.purgeInquiryConversation("conv_ok")).thenReturn(true);

        assertThat(scheduler.purgeStaleInquiries()).isEqualTo(1);
        verify(auditService).log(eq("conversation"), eq(ok.getId()), eq("INQUIRY_PURGED"), isNull(), anyMap());
    }

    @Test
    void holdAppearingAfterTheFirstCheck_releasesTheReservation_andNeverDeletesInFirestore() {
        ConversationEntity conv = inquiry("conv_late_hold");
        stale(conv);
        reserved(conv, 1);
        when(hold.holds(isNull(), anyCollection())).thenReturn(false, true);

        assertThat(scheduler.purgeStaleInquiries()).isZero();

        verify(conversationRepository).releaseInquiryReservation(conv.getId(), NOW_LDT);
        verify(firestoreService, never()).purgeInquiryConversation(any());
        verify(auditService, never()).log(any(), any(), any(), any(), anyMap());
    }

    @Test
    void recentMessageAppearingAfterTheReservation_releasesIt_andNeverDeletesInFirestore() {
        ConversationEntity conv = inquiry("conv_late_msg");
        stale(conv);
        reserved(conv, 1);
        when(firestoreService.lastMessageInstant("conv_late_msg")).thenReturn(
                java.util.Optional.empty(), java.util.Optional.of(Instant.parse("2026-11-12T08:00:00Z")));

        assertThat(scheduler.purgeStaleInquiries()).isZero();

        verify(conversationRepository).releaseInquiryReservation(conv.getId(), NOW_LDT);
        verify(conversationRepository).markInquiryActivity(conv.getId(), LocalDateTime.of(2026, 11, 12, 8, 0));
        verify(firestoreService, never()).purgeInquiryConversation(any());
    }

    @Test
    void unreadableDateOnTheFirstCheck_keepsTheConversation_withoutUpdateNorFirestore() {
        ConversationEntity conv = inquiry("conv_unreadable");
        stale(conv);
        when(firestoreService.lastMessageInstant("conv_unreadable"))
                .thenThrow(new FirestoreService.UnreadableTimestampException("bad"));

        assertThat(scheduler.purgeStaleInquiries()).isZero();

        verify(conversationRepository, never()).reserveStaleInquiry(any(), any(), any());
        verify(firestoreService, never()).purgeInquiryConversation(any());
    }

    @Test
    void unreadableDateOnTheSecondCheck_releasesTheReservation() {
        ConversationEntity conv = inquiry("conv_unreadable2");
        stale(conv);
        reserved(conv, 1);
        when(firestoreService.lastMessageInstant("conv_unreadable2"))
                .thenReturn(java.util.Optional.empty())
                .thenThrow(new FirestoreService.UnreadableTimestampException("bad"));

        assertThat(scheduler.purgeStaleInquiries()).isZero();

        verify(conversationRepository).releaseInquiryReservation(conv.getId(), NOW_LDT);
        verify(firestoreService, never()).purgeInquiryConversation(any());
    }

    @Test
    void firestoreException_afterTheReservation_releasesIt() {
        ConversationEntity conv = inquiry("conv_throws");
        stale(conv);
        reserved(conv, 1);
        when(firestoreService.purgeInquiryConversation("conv_throws")).thenThrow(new IllegalStateException("boom"));

        assertThat(scheduler.purgeStaleInquiries()).isZero();

        verify(conversationRepository).releaseInquiryReservation(conv.getId(), NOW_LDT);
    }

    @Test
    void scheduledEntryPoint_runsThePass() {
        when(conversationRepository.findStaleInquiries(any())).thenReturn(List.of());
        scheduler.scheduledPurge();
        verify(conversationRepository).findStaleInquiries(any());
    }

    // ── FirestoreService#lastMessageInstant ─────────────────────────────────────

    @Test
    void lastMessageInstant_isEmpty_whenFirestoreIsDisabled() {
        assertThat(new FirestoreService(null).lastMessageInstant("conv_x")).isEmpty();
    }

    /**
     * Firestore simulé : document parent ({@code lastMessageAt}, ou document absent) ET dernier message
     * ({@code sentAt}, ou aucun message). Les deux sont toujours lus : stubs indulgents pour les branches.
     */
    private static Firestore firestoreWith(boolean parentExists, Object parentLastMessageAt,
                                           boolean hasMessage, Object latestSentAt) {
        Firestore firestore = mock(Firestore.class);
        CollectionReference conversations = mock(CollectionReference.class);
        CollectionReference messages = mock(CollectionReference.class);
        com.google.cloud.firestore.Query ordered = mock(com.google.cloud.firestore.Query.class);
        com.google.cloud.firestore.Query limited = mock(com.google.cloud.firestore.Query.class);
        com.google.cloud.firestore.QuerySnapshot result = mock(com.google.cloud.firestore.QuerySnapshot.class);
        DocumentReference convRef = mock(DocumentReference.class);
        DocumentSnapshot snap = mock(DocumentSnapshot.class);
        lenient().when(firestore.collection("conversations")).thenReturn(conversations);
        lenient().when(conversations.document("conv_x")).thenReturn(convRef);
        lenient().when(convRef.get()).thenReturn(ApiFutures.immediateFuture(snap));
        lenient().when(snap.exists()).thenReturn(parentExists);
        lenient().when(snap.get("lastMessageAt")).thenReturn(parentLastMessageAt);
        lenient().when(convRef.collection("messages")).thenReturn(messages);
        lenient().when(messages.orderBy("sentAt", com.google.cloud.firestore.Query.Direction.DESCENDING)).thenReturn(ordered);
        lenient().when(ordered.limit(1)).thenReturn(limited);
        lenient().when(limited.get()).thenReturn(ApiFutures.immediateFuture(result));
        lenient().when(result.isEmpty()).thenReturn(!hasMessage);
        if (hasMessage) {
            com.google.cloud.firestore.QueryDocumentSnapshot doc = mock(com.google.cloud.firestore.QueryDocumentSnapshot.class);
            lenient().when(doc.get("sentAt")).thenReturn(latestSentAt);
            lenient().when(result.getDocuments()).thenReturn(java.util.List.of(doc));
        }
        return firestore;
    }

    private static java.util.Optional<Instant> lastMessage(boolean parentExists, Object parentLastMessageAt,
                                                           boolean hasMessage, Object latestSentAt) {
        return new FirestoreService(firestoreWith(parentExists, parentLastMessageAt, hasMessage, latestSentAt))
                .lastMessageInstant("conv_x");
    }

    @Test
    void lastMessageInstant_readsTheParentLastMessageAt_whenThereIsNoMessage() {
        assertThat(lastMessage(true, "2026-11-10T08:00:00Z", false, null))
                .contains(Instant.parse("2026-11-10T08:00:00Z"));
    }

    @Test
    void lastMessageInstant_takesTheLatestMessage_whenTheParentOnlyCarriesTheCreationDate() {
        // lastMessageAt du parent est posé dès la création : un message plus récent doit l'emporter.
        assertThat(lastMessage(true, "2026-09-01T08:00:00Z", true, "2026-11-10T08:00:00Z"))
                .contains(Instant.parse("2026-11-10T08:00:00Z"));
    }

    @Test
    void lastMessageInstant_keepsTheParentDate_whenItIsTheMostRecent() {
        assertThat(lastMessage(true, "2026-11-10T08:00:00Z", true, "2026-09-01T08:00:00Z"))
                .contains(Instant.parse("2026-11-10T08:00:00Z"));
    }

    @Test
    void lastMessageInstant_withoutParentDocument_usesTheLatestMessage() {
        assertThat(lastMessage(false, null, true, "2026-11-09T08:00:00Z"))
                .contains(Instant.parse("2026-11-09T08:00:00Z"));
    }

    @Test
    void lastMessageInstant_unexpectedParentType_isUnreadable_notEmpty() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> lastMessage(true, 1731484800000L, false, null))
                .isInstanceOf(FirestoreService.UnreadableTimestampException.class);
        // Même avec un message lisible : une date illisible ne vaut jamais « inactive ».
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> lastMessage(true, 1731484800000L, true, "2026-11-09T08:00:00Z"))
                .isInstanceOf(FirestoreService.UnreadableTimestampException.class);
    }

    @Test
    void lastMessageInstant_malformedParentString_isUnreadable() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> lastMessage(true, "hier soir", false, null))
                .isInstanceOf(FirestoreService.UnreadableTimestampException.class);
    }

    @Test
    void lastMessageInstant_readsFirestoreTimestamps() {
        Instant parent = Instant.parse("2026-11-08T08:00:00Z");
        Instant message = Instant.parse("2026-11-10T08:00:00Z");
        assertThat(lastMessage(true, com.google.cloud.Timestamp.ofTimeSecondsAndNanos(parent.getEpochSecond(), 0),
                true, com.google.cloud.Timestamp.ofTimeSecondsAndNanos(message.getEpochSecond(), 0)))
                .contains(message);
    }

    @Test
    void lastMessageInstant_absentField_andNoMessage_isEmpty() {
        assertThat(lastMessage(true, null, false, null)).isEmpty();
        assertThat(lastMessage(false, null, false, null)).isEmpty();
    }

    @Test
    void lastMessageInstant_unreadableLatestMessage_isUnreadable() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> lastMessage(true, null, true, 42))
                .isInstanceOf(FirestoreService.UnreadableTimestampException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> lastMessage(true, null, true, null))
                .isInstanceOf(FirestoreService.UnreadableTimestampException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> lastMessage(true, "2026-11-10T08:00:00Z", true, "pas une date"))
                .isInstanceOf(FirestoreService.UnreadableTimestampException.class);
    }

    @Test
    void keepBecauseActive_usesTheMostRecentOfTheParentDateAndTheLatestMessage() {
        // Bout en bout avec le vrai FirestoreService : parent à la date de création (avant la limite),
        // dernier message récent. La conversation est gardée et son activité datée du message.
        ConversationEntity conv = inquiry("conv_x");
        stale(conv);
        InquiryInactivityScheduler withRealFirestore = new InquiryInactivityScheduler(conversationRepository,
                new FirestoreService(firestoreWith(true, "2026-09-01T08:00:00Z", true, "2026-11-10T08:00:00Z")),
                auditService, List.of(hold), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(withRealFirestore.purgeStaleInquiries()).isZero();

        verify(conversationRepository).markInquiryActivity(conv.getId(), LocalDateTime.of(2026, 11, 10, 8, 0));
        verify(conversationRepository, never()).reserveStaleInquiry(any(), any(), any());
    }

    @Test
    void lastMessageInstant_failure_isRaised() {
        Firestore firestore = mock(Firestore.class);
        when(firestore.collection("conversations")).thenThrow(new IllegalStateException("down"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new FirestoreService(firestore).lastMessageInstant("conv_x"))
                .isInstanceOf(RuntimeException.class);
    }

    // ── FirestoreService#purgeInquiryConversation ───────────────────────────────

    @Test
    void firestore_noOpSuccess_whenDisabled() {
        assertThat(new FirestoreService(null).purgeInquiryConversation("conv_x")).isTrue();
    }

    @Test
    void firestore_deletesConversationRecursively_andTheQuotaDocumentTogether() throws Exception {
        Firestore firestore = mock(Firestore.class);
        CollectionReference conversations = mock(CollectionReference.class);
        CollectionReference quotas = mock(CollectionReference.class);
        DocumentReference convRef = mock(DocumentReference.class);
        DocumentReference quotaRef = mock(DocumentReference.class);
        DocumentSnapshot snap = mock(DocumentSnapshot.class);
        when(firestore.collection("conversations")).thenReturn(conversations);
        when(firestore.collection("inquiryQuota")).thenReturn(quotas);
        when(conversations.document("conv_x")).thenReturn(convRef);
        when(quotas.document("conv_x")).thenReturn(quotaRef);
        when(convRef.get()).thenReturn(ApiFutures.immediateFuture(snap));
        when(snap.exists()).thenReturn(false);
        when(firestore.recursiveDelete(convRef)).thenReturn(ApiFutures.immediateFuture(null));
        when(quotaRef.delete()).thenReturn(ApiFutures.immediateFuture(mock(WriteResult.class)));

        assertThat(new FirestoreService(firestore).purgeInquiryConversation("conv_x")).isTrue();

        verify(firestore).recursiveDelete(convRef);
        verify(quotaRef).delete();
    }

    @Test
    void firestore_failureOnMessages_returnsFalse_andNeverDeletesTheQuotaAlone() throws Exception {
        Firestore firestore = mock(Firestore.class);
        CollectionReference conversations = mock(CollectionReference.class);
        DocumentReference convRef = mock(DocumentReference.class);
        DocumentSnapshot snap = mock(DocumentSnapshot.class);
        when(firestore.collection("conversations")).thenReturn(conversations);
        when(conversations.document("conv_x")).thenReturn(convRef);
        when(convRef.get()).thenReturn(ApiFutures.immediateFuture(snap));
        when(snap.exists()).thenReturn(false);
        when(firestore.recursiveDelete(convRef)).thenReturn(ApiFutures.immediateFailedFuture(new RuntimeException("down")));

        assertThat(new FirestoreService(firestore).purgeInquiryConversation("conv_x")).isFalse();

        verify(firestore, never()).collection("inquiryQuota");
    }
}
