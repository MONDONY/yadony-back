package com.yadony.api.messaging;

import com.yadony.api.common.deletion.ImpactFinding;
import com.yadony.api.common.deletion.ImpactSeverity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessagingDeletionImpactContributorTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock ConversationRepository conversationRepository;

    @Test
    @DisplayName("les conversations actives sont rapportées en avertissement")
    void activeConversations_areReported() {
        when(conversationRepository.countActiveByParticipant(USER_ID)).thenReturn(4L);

        List<ImpactFinding> findings =
                new MessagingDeletionImpactContributor(conversationRepository).contribute(USER_ID);

        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().severity()).isEqualTo(ImpactSeverity.WARNING);
        assertThat(findings.getFirst().code()).isEqualTo("ACTIVE_CONVERSATION");
        assertThat(findings.getFirst().count()).isEqualTo(4);
    }

    @Test
    @DisplayName("aucune conversation active, rien à rapporter")
    void noConversation_reportsNothing() {
        when(conversationRepository.countActiveByParticipant(USER_ID)).thenReturn(0L);

        assertThat(new MessagingDeletionImpactContributor(conversationRepository).contribute(USER_ID))
                .isEmpty();
    }
}
