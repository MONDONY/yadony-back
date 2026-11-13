package com.yadony.api.messaging;

import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.WriteResult;
import com.yadony.api.common.AuditService;
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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
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

    private InquiryInactivityScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new InquiryInactivityScheduler(conversationRepository, firestoreService, auditService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ConversationEntity inquiry(String firestoreId) {
        return ConversationEntity.forInquiry(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), firestoreId);
    }

    @Test
    void selectsConversationsInactiveForThirtyDays_softDeletesThem_andAudits() {
        ConversationEntity conv = inquiry("conv_stale");
        when(conversationRepository.findStaleInquiries(LocalDateTime.of(2026, 10, 14, 12, 0)))
                .thenReturn(List.of(conv));
        when(firestoreService.purgeInquiryConversation("conv_stale")).thenReturn(true);

        assertThat(scheduler.purgeStaleInquiries()).isEqualTo(1);

        assertThat(conv.getDeletedAt()).isNotNull();
        InOrder order = inOrder(firestoreService, conversationRepository);
        order.verify(firestoreService).purgeInquiryConversation("conv_stale");
        order.verify(conversationRepository).save(conv);
        verify(auditService).log(eq("conversation"), eq(conv.getId()), eq("INQUIRY_PURGED"), isNull(), anyMap());
    }

    @Test
    void secondRun_isANoOp_whenNothingIsSelectedAnymore() {
        ConversationEntity conv = inquiry("conv_once");
        when(conversationRepository.findStaleInquiries(any())).thenReturn(List.of(conv)).thenReturn(List.of());
        when(firestoreService.purgeInquiryConversation("conv_once")).thenReturn(true);

        assertThat(scheduler.purgeStaleInquiries()).isEqualTo(1);
        assertThat(scheduler.purgeStaleInquiries()).isZero();

        verify(firestoreService).purgeInquiryConversation("conv_once");
        verify(conversationRepository).save(conv);
    }

    @Test
    void firestoreFailure_keepsTheRowNotDeleted_soTheNextRunRetries() {
        ConversationEntity conv = inquiry("conv_fs_fail");
        when(conversationRepository.findStaleInquiries(any())).thenReturn(List.of(conv));
        when(firestoreService.purgeInquiryConversation("conv_fs_fail")).thenReturn(false);

        assertThat(scheduler.purgeStaleInquiries()).isZero();

        assertThat(conv.getDeletedAt()).isNull();
        verify(conversationRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), anyMap());
    }

    @Test
    void oneFailingConversation_doesNotStopTheOthers() {
        ConversationEntity broken = inquiry("conv_broken");
        ConversationEntity ok = inquiry("conv_ok");
        when(conversationRepository.findStaleInquiries(any())).thenReturn(List.of(broken, ok));
        when(firestoreService.purgeInquiryConversation("conv_broken")).thenThrow(new IllegalStateException("boom"));
        when(firestoreService.purgeInquiryConversation("conv_ok")).thenReturn(true);

        assertThat(scheduler.purgeStaleInquiries()).isEqualTo(1);

        assertThat(ok.getDeletedAt()).isNotNull();
        assertThat(broken.getDeletedAt()).isNull();
    }

    @Test
    void databaseFailureOnOne_doesNotStopTheOthers() {
        ConversationEntity first = inquiry("conv_db_fail");
        ConversationEntity second = inquiry("conv_after");
        when(conversationRepository.findStaleInquiries(any())).thenReturn(List.of(first, second));
        when(firestoreService.purgeInquiryConversation(any())).thenReturn(true);
        when(conversationRepository.save(first)).thenThrow(new IllegalStateException("db down"));

        assertThat(scheduler.purgeStaleInquiries()).isEqualTo(1);

        verify(conversationRepository).save(second);
    }

    @Test
    void scheduledEntryPoint_runsThePass() {
        when(conversationRepository.findStaleInquiries(any())).thenReturn(List.of());
        scheduler.scheduledPurge();
        verify(conversationRepository).findStaleInquiries(any());
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
