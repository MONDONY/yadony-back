package com.yadony.api.messaging;

import com.yadony.api.common.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationMessagePurgeServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    @Mock ConversationRepository conversationRepository;
    @Mock MessagingImageRetentionService imageRetention;
    @Mock FirestoreService firestoreService;
    @Mock AuditService auditService;

    private ConversationMessagePurgeService service;

    @BeforeEach
    void setUp() {
        service = new ConversationMessagePurgeService(conversationRepository, imageRetention, firestoreService,
                auditService, 3, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void purgeDue_selectsConversationsDeliveredAtLeastThreeDaysAgo_andPurgesThem() {
        ConversationEntity conv = conversation("conv_a");
        when(conversationRepository.findMessagesToPurge(LocalDateTime.of(2026, 10, 12, 12, 0)))
                .thenReturn(List.of(conv));
        when(imageRetention.purgeConversation(conv, null)).thenReturn(true);
        when(firestoreService.purgeMessages("conv_a")).thenReturn(true);

        assertThat(service.purgeDue()).isEqualTo(1);

        assertThat(conv.getMessagesPurgedAt()).isEqualTo(LocalDateTime.of(2026, 10, 15, 12, 0));
        assertThat(conv.isMessagesPurged()).isTrue();
        verify(conversationRepository).save(conv);
        verify(auditService).log(eq("conversation"), eq(conv.getId()), eq("CONVERSATION_MESSAGES_PURGED"),
                isNull(), anyMap());
    }

    @Test
    void heldConversation_isLeftUntouched_untilTheProcedureCloses() {
        ConversationEntity conv = conversation("conv_held");
        when(conversationRepository.findMessagesToPurge(any())).thenReturn(List.of(conv));
        when(imageRetention.purgeConversation(conv, null)).thenReturn(false);

        assertThat(service.purgeDue()).isZero();

        verify(firestoreService, never()).purgeMessages(any());
        assertThat(conv.isMessagesPurged()).isFalse();
        verify(conversationRepository, never()).save(any());
    }

    @Test
    void firestoreFailure_leavesTheConversationForTheNextPass() {
        ConversationEntity conv = conversation("conv_fail");
        when(conversationRepository.findMessagesToPurge(any())).thenReturn(List.of(conv));
        when(imageRetention.purgeConversation(conv, null)).thenReturn(true);
        when(firestoreService.purgeMessages("conv_fail")).thenReturn(false);

        assertThat(service.purgeDue()).isZero();

        assertThat(conv.isMessagesPurged()).isFalse();
        verify(auditService, never()).log(any(), any(), any(), any(), anyMap());
    }

    @Test
    void oneFailingConversation_doesNotStopTheOthers() {
        ConversationEntity broken = conversation("conv_broken");
        ConversationEntity ok = conversation("conv_ok");
        when(conversationRepository.findMessagesToPurge(any())).thenReturn(List.of(broken, ok));
        when(imageRetention.purgeConversation(broken, null)).thenThrow(new IllegalStateException("boom"));
        when(imageRetention.purgeConversation(ok, null)).thenReturn(true);
        when(firestoreService.purgeMessages("conv_ok")).thenReturn(true);

        assertThat(service.purgeDue()).isEqualTo(1);
        assertThat(ok.isMessagesPurged()).isTrue();
        assertThat(broken.isMessagesPurged()).isFalse();
    }

    @Test
    void markMessagesPurged_keepsTheFirstDate() {
        ConversationEntity conv = conversation("conv_twice");
        LocalDateTime first = LocalDateTime.of(2026, 10, 1, 8, 0);
        conv.markMessagesPurged(first);
        conv.markMessagesPurged(first.plusDays(1));
        assertThat(conv.getMessagesPurgedAt()).isEqualTo(first);
        assertThat(service.purgeAfterDays()).isEqualTo(3);
    }

    private static ConversationEntity conversation(String firestoreId) {
        return new ConversationEntity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), firestoreId);
    }
}
