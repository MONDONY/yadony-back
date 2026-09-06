package com.yadony.api.support;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.stripe.AdminAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupportUnreadServiceTest {

    @Mock private SupportTicketRepository ticketRepository;
    @Mock private SupportMessageRepository messageRepository;
    @Mock private SupportPredefinedReplyRepository replyRepository;
    @Mock private AdminAlertService adminAlertService;
    @Mock private AuditService auditService;
    @Mock private SupportAttachmentService attachmentService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private SupportTicketService service;

    private UserEntity user;
    private SupportTicketEntity ticket;

    @BeforeEach
    void setUp() {
        user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        ticket = new SupportTicketEntity();
        ReflectionTestUtils.setField(ticket, "id", UUID.randomUUID());
        ticket.setUserId(user.getId());
        ticket.setStatus(SupportTicketStatus.WAITING_USER);
    }

    /** Jamais ouvert : toute reponse admin est non lue. */
    @Test
    void countsEveryAdminMessageWhenTheTicketWasNeverOpened() {
        ticket.setUserLastReadAt(null);
        when(messageRepository.countByTicketIdAndAuthorType(
                ticket.getId(), SupportMessageAuthorType.ADMIN)).thenReturn(3L);

        assertThat(service.unreadCount(ticket)).isEqualTo(3L);
    }

    @Test
    void countsOnlyAdminMessagesPostedAfterTheLastRead() {
        LocalDateTime readAt = LocalDateTime.now(ZoneOffset.UTC).minusHours(2);
        ticket.setUserLastReadAt(readAt);
        when(messageRepository.countByTicketIdAndAuthorTypeAndCreatedAtAfter(
                ticket.getId(), SupportMessageAuthorType.ADMIN, readAt)).thenReturn(1L);

        assertThat(service.unreadCount(ticket)).isEqualTo(1L);
    }

    /** Lire n'est pas repondre : le statut ne bouge pas. */
    @Test
    void markingReadStampsTheDateWithoutTouchingTheStatus() {
        when(ticketRepository.findById(ticket.getId())).thenReturn(Optional.of(ticket));

        service.markRead(user, ticket.getId());

        assertThat(ticket.getUserLastReadAt()).isNotNull();
        assertThat(ticket.getStatus()).isEqualTo(SupportTicketStatus.WAITING_USER);
        verify(ticketRepository).save(ticket);
    }

    /** Le ticket d'autrui est introuvable, jamais interdit. */
    @Test
    void refusesToMarkSomeoneElsesTicketWithA404() {
        UserEntity intruder = new UserEntity();
        ReflectionTestUtils.setField(intruder, "id", UUID.randomUUID());
        when(ticketRepository.findById(ticket.getId())).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.markRead(intruder, ticket.getId()))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("introuvable");
        verify(ticketRepository, never()).save(any());
    }
}
