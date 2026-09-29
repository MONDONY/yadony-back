package com.yadony.api.support;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.support.events.SupportMessageCreatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * L'administrateur est ici un simple {@code UUID} : les comptes du back-office
 * vivent dans {@code admin_users}, pas dans {@code users}, et le service n'a
 * besoin que de l'identite pour assigner et auditer.
 */
@ExtendWith(MockitoExtension.class)
class SupportTicketServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ADMIN_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID OTHER_ADMIN_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID TICKET_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Mock SupportTicketRepository ticketRepository;
    @Mock SupportMessageRepository messageRepository;
    @Mock SupportPredefinedReplyRepository replyRepository;
    @Mock AdminAlertService adminAlertService;
    @Mock AuditService auditService;
    @Mock SupportAttachmentService attachmentService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock UserRepository userRepository;

    private SupportTicketService service;

    @BeforeEach
    void setUp() {
        service = new SupportTicketService(
                ticketRepository,
                messageRepository,
                replyRepository,
                adminAlertService,
                auditService,
                attachmentService,
                eventPublisher,
                userRepository);
    }

    // ------------------------------------------------ conversation initiee par l'admin

    @Test
    void adminStartTicket_opensAThreadWaitingForTheUser_assignedToTheAdmin() {
        UserEntity target = user(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(target));
        stubSaves();
        when(attachmentService.adminPrefix(ADMIN_ID)).thenReturn("support/admin/" + ADMIN_ID + "/");
        when(attachmentService.requireOwnedKeys(any(), eq("support/admin/" + ADMIN_ID + "/"))).thenReturn(List.of());

        SupportTicketEntity ticket = service.adminStartTicket(
                USER_ID, ADMIN_ID, "kyc", "  Votre verification  ", "  Bonjour, il manque une photo.  ", null);

        assertThat(ticket.getId()).isEqualTo(TICKET_ID);
        assertThat(ticket.getUserId()).isEqualTo(USER_ID);
        assertThat(ticket.getAssignedAdminId()).isEqualTo(ADMIN_ID);
        assertThat(ticket.getStatus()).isEqualTo(SupportTicketStatus.WAITING_USER);
        assertThat(ticket.getCategory()).isEqualTo("KYC");
        assertThat(ticket.getSubject()).isEqualTo("Votre verification");
        assertThat(ticket.getPriority()).isEqualTo(SupportPriority.NORMAL);
        assertThat(ticket.getLastMessageAt()).isNotNull();

        ArgumentCaptor<SupportMessageEntity> message = ArgumentCaptor.forClass(SupportMessageEntity.class);
        verify(messageRepository).save(message.capture());
        assertThat(message.getValue().getAuthorType()).isEqualTo(SupportMessageAuthorType.ADMIN);
        assertThat(message.getValue().getAuthorId()).isEqualTo(ADMIN_ID);
        assertThat(message.getValue().getContent()).isEqualTo("Bonjour, il manque une photo.");

        ArgumentCaptor<SupportMessageCreatedEvent> event = ArgumentCaptor.forClass(SupportMessageCreatedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().getAuthorType()).isEqualTo(SupportMessageAuthorType.ADMIN);
        assertThat(event.getValue().getOwnerUserId()).isEqualTo(USER_ID);
        assertThat(event.getValue().getTicketId()).isEqualTo(TICKET_ID);
        assertThat(event.getValue().isStartedByAdmin()).isTrue();
        assertThat(event.getValue().getSubject()).isEqualTo("Votre verification");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("support_ticket"), eq(TICKET_ID), eq("SUPPORT_TICKET_ADMIN_STARTED"),
                eq(ADMIN_ID), details.capture());
        assertThat(details.getValue())
                .containsEntry("adminId", ADMIN_ID.toString())
                .containsEntry("userId", USER_ID.toString())
                .containsEntry("ticketId", TICKET_ID.toString())
                .containsEntry("category", "KYC");

        verifyNoInteractions(adminAlertService);
    }

    @Test
    void adminStartTicket_defaultsTheCategoryToOther() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
        stubSaves();
        when(attachmentService.requireOwnedKeys(any(), any())).thenReturn(List.of());

        SupportTicketEntity ticket = service.adminStartTicket(
                USER_ID, ADMIN_ID, null, "Question", "Bonjour", null);

        assertThat(ticket.getCategory()).isEqualTo("OTHER");
    }

    /** Un compte suspendu ou banni reste joignable : c'est souvent pour lui expliquer. */
    @Test
    void adminStartTicket_allowsASuspendedAccount() {
        UserEntity suspended = user(USER_ID);
        suspended.setStatus(UserStatus.BANNED);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(suspended));
        stubSaves();
        when(attachmentService.requireOwnedKeys(any(), any())).thenReturn(List.of());

        assertThat(service.adminStartTicket(USER_ID, ADMIN_ID, "ACCOUNT", "Compte", "Bonjour", null)
                .getStatus()).isEqualTo(SupportTicketStatus.WAITING_USER);
    }

    @Test
    void adminStartTicket_attachesAdminKeys() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
        stubSaves();
        List<String> keys = List.of("support/admin/" + ADMIN_ID + "/a.png");
        when(attachmentService.adminPrefix(ADMIN_ID)).thenReturn("support/admin/" + ADMIN_ID + "/");
        when(attachmentService.requireOwnedKeys(keys, "support/admin/" + ADMIN_ID + "/")).thenReturn(keys);

        service.adminStartTicket(USER_ID, ADMIN_ID, "OTHER", "Capture", null, keys);

        verify(attachmentService).attach(any(), eq(keys), eq("image/png"));
    }

    @Test
    void adminStartTicket_refusesAttachmentsOutsideTheAdminPrefix() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
        when(attachmentService.adminPrefix(ADMIN_ID)).thenReturn("support/admin/" + ADMIN_ID + "/");
        when(attachmentService.requireOwnedKeys(any(), eq("support/admin/" + ADMIN_ID + "/")))
                .thenThrow(new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "support-attachment-not-owned", "Piece jointe invalide", "x"));

        assertThatThrownBy(() -> service.adminStartTicket(USER_ID, ADMIN_ID, "OTHER", "Sujet", "Bonjour",
                List.of("support/user/" + USER_ID + "/a.jpg")))
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        verify(ticketRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    /** Un compte supprime sort du @Where de UserEntity : findById ne le voit plus. */
    @Test
    void adminStartTicket_returns404ForAnUnknownOrDeletedUser() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.adminStartTicket(USER_ID, ADMIN_ID, "OTHER", "Sujet", "Bonjour", null))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getErrorCode()).isEqualTo("user-not-found");
                });
        verify(ticketRepository, never()).save(any());
        verifyNoInteractions(auditService, eventPublisher);
    }

    @Test
    void adminStartTicket_validatesSubjectAndMessage() {
        assertThatThrownBy(() -> service.adminStartTicket(USER_ID, ADMIN_ID, "OTHER", " ", "Bonjour", null))
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThatThrownBy(() -> service.adminStartTicket(USER_ID, ADMIN_ID, "OTHER", "Sujet", " ", null))
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        assertThatThrownBy(() -> service.adminStartTicket(USER_ID, ADMIN_ID, "NOPE", "Sujet", "Bonjour", null))
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        verifyNoInteractions(ticketRepository, eventPublisher);
    }

    /** Une reponse ordinaire ne se presente pas comme une nouvelle conversation. */
    @Test
    void adminReply_publishesAnEventThatIsNotAConversationStart() {
        SupportTicketEntity ticket = ticket(USER_ID, SupportTicketStatus.ASSIGNED);
        ticket.setAssignedAdminId(ADMIN_ID);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        when(messageRepository.save(any(SupportMessageEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(attachmentService.requireOwnedKeys(any(), any())).thenReturn(List.of());

        service.adminReply(TICKET_ID, ADMIN_ID, "On regarde.", null);

        ArgumentCaptor<SupportMessageCreatedEvent> event = ArgumentCaptor.forClass(SupportMessageCreatedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().isStartedByAdmin()).isFalse();
    }

    private void stubSaves() {
        when(ticketRepository.save(any(SupportTicketEntity.class))).thenAnswer(inv -> {
            SupportTicketEntity t = inv.getArgument(0);
            ReflectionTestUtils.setField(t, "id", TICKET_ID);
            return t;
        });
        when(messageRepository.save(any(SupportMessageEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createTicket_storesTicketFirstMessage_andRaisesTelegramAlert() {
        UserEntity user = user(USER_ID);
        when(ticketRepository.save(any(SupportTicketEntity.class))).thenAnswer(inv -> {
            SupportTicketEntity ticket = inv.getArgument(0);
            ReflectionTestUtils.setField(ticket, "id", TICKET_ID);
            return ticket;
        });
        when(messageRepository.save(any(SupportMessageEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        when(attachmentService.requireOwnedKeys(any(), any())).thenReturn(List.of());
        SupportTicketEntity ticket = service.createTicket(
                user,
                "PAYMENT",
                "Paiement bloque",
                "Je ne vois pas le remboursement.",
                null);

        assertThat(ticket.getId()).isEqualTo(TICKET_ID);
        assertThat(ticket.getUserId()).isEqualTo(USER_ID);
        assertThat(ticket.getStatus()).isEqualTo(SupportTicketStatus.NEW);
        assertThat(ticket.getPriority()).isEqualTo(SupportPriority.NORMAL);
        assertThat(ticket.getLastMessageAt()).isNotNull();
        verify(messageRepository).save(any(SupportMessageEntity.class));
        verify(adminAlertService).raise(
                eq("SUPPORT_TICKET_CREATED"),
                eq("Nouveau ticket support: Paiement bloque"),
                any(Map.class));
        verify(auditService).log(eq("support_ticket"), eq(TICKET_ID), eq("SUPPORT_TICKET_CREATED"), eq(USER_ID), any(Map.class));

        ArgumentCaptor<SupportMessageCreatedEvent> eventCaptor = ArgumentCaptor.forClass(SupportMessageCreatedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        SupportMessageCreatedEvent event = eventCaptor.getValue();
        assertThat(event.getOwnerUserId()).isEqualTo(USER_ID);
        assertThat(event.getTicketId()).isEqualTo(TICKET_ID);
        assertThat(event.getAuthorType()).isEqualTo(SupportMessageAuthorType.USER);
    }

    @Test
    void createTicket_survivesAlertingFailure() {
        UserEntity user = user(USER_ID);
        when(ticketRepository.save(any(SupportTicketEntity.class))).thenAnswer(inv -> {
            SupportTicketEntity ticket = inv.getArgument(0);
            ReflectionTestUtils.setField(ticket, "id", TICKET_ID);
            return ticket;
        });
        when(messageRepository.save(any(SupportMessageEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new IllegalStateException("telegram down"))
                .when(adminAlertService).raise(any(), any(), any());

        when(attachmentService.requireOwnedKeys(any(), any())).thenReturn(List.of());
        SupportTicketEntity ticket = service.createTicket(
                user, "payment", "Paiement bloque", "Je ne vois pas le remboursement.", null);

        assertThat(ticket.getId()).isEqualTo(TICKET_ID);
    }

    @Test
    void createTicket_rejectsUnknownCategory() {
        assertThatThrownBy(() -> service.createTicket(user(USER_ID), "NOPE", "Sujet", "Message", null))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(SupportTicketServiceTest::statusOf)
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        verifyNoInteractions(ticketRepository, messageRepository);
    }

    @Test
    void createTicket_rejectsBlankSubject() {
        assertThatThrownBy(() -> service.createTicket(user(USER_ID), "PAYMENT", "   ", "Message", null))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(SupportTicketServiceTest::statusOf)
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void createTicket_rejectsOverlongMessage() {
        String tooLong = "x".repeat(4001);
        assertThatThrownBy(() -> service.createTicket(user(USER_ID), "PAYMENT", "Sujet", tooLong, null))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(SupportTicketServiceTest::statusOf)
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void userReply_appendsMessageAndWaitsForSupport() {
        SupportTicketEntity ticket = ticket(USER_ID, SupportTicketStatus.WAITING_USER);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        when(messageRepository.save(any(SupportMessageEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        when(attachmentService.requireOwnedKeys(any(), any())).thenReturn(List.of());
        SupportMessageEntity message = service.userReply(user(USER_ID), TICKET_ID, "  Toujours bloque  ", null);

        assertThat(message.getAuthorType()).isEqualTo(SupportMessageAuthorType.USER);
        assertThat(message.getAuthorId()).isEqualTo(USER_ID);
        assertThat(message.getContent()).isEqualTo("Toujours bloque");
        assertThat(ticket.getStatus()).isEqualTo(SupportTicketStatus.WAITING_SUPPORT);
    }

    @Test
    void userReply_rejectsResolvedTicket() {
        SupportTicketEntity ticket = ticket(USER_ID, SupportTicketStatus.RESOLVED);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.userReply(user(USER_ID), TICKET_ID, "Toujours bloque", null))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(SupportTicketServiceTest::statusOf)
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    /** 404 et non 403 : un 403 confirmerait au demandeur que le ticket existe. */
    @Test
    void userReply_rejectsTicketOwnedByAnotherUser() {
        SupportTicketEntity ticket = ticket(OTHER_USER_ID, SupportTicketStatus.WAITING_USER);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.userReply(user(USER_ID), TICKET_ID, "Bonjour", null))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(SupportTicketServiceTest::statusOf)
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getUserTicket_rejectsUnknownTicket() {
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getUserTicket(user(USER_ID), TICKET_ID))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(SupportTicketServiceTest::statusOf)
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void adminReply_requiresAssignedAdmin() {
        SupportTicketEntity ticket = ticket(USER_ID, SupportTicketStatus.NEW);
        ticket.setAssignedAdminId(OTHER_ADMIN_ID);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.adminReply(TICKET_ID, ADMIN_ID, "Je regarde.", null))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(SupportTicketServiceTest::statusOf)
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void adminReply_requiresAnyAssignment() {
        SupportTicketEntity ticket = ticket(USER_ID, SupportTicketStatus.NEW);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.adminReply(TICKET_ID, ADMIN_ID, "Je regarde.", null))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(SupportTicketServiceTest::statusOf)
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void assignThenAdminReplyThenResolve_movesTicketThroughSupportFlow() {
        SupportTicketEntity ticket = ticket(USER_ID, SupportTicketStatus.NEW);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        when(messageRepository.save(any(SupportMessageEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        when(attachmentService.requireOwnedKeys(any(), any())).thenReturn(List.of());
        SupportTicketEntity assigned = service.assign(TICKET_ID, ADMIN_ID);
        SupportMessageEntity reply = service.adminReply(TICKET_ID, ADMIN_ID, "On verifie le paiement.", null);
        SupportTicketEntity resolved = service.resolve(TICKET_ID, ADMIN_ID);

        assertThat(assigned.getAssignedAdminId()).isEqualTo(ADMIN_ID);
        assertThat(reply.getAuthorType()).isEqualTo(SupportMessageAuthorType.ADMIN);
        assertThat(reply.getAuthorId()).isEqualTo(ADMIN_ID);
        assertThat(resolved.getStatus()).isEqualTo(SupportTicketStatus.RESOLVED);
        assertThat(resolved.getResolvedAt()).isNotNull();
        verify(auditService).log(eq("support_ticket"), eq(TICKET_ID), eq("SUPPORT_TICKET_ASSIGNED"), eq(ADMIN_ID), any(Map.class));
        verify(auditService).log(eq("support_ticket"), eq(TICKET_ID), eq("SUPPORT_TICKET_ADMIN_REPLIED"), eq(ADMIN_ID), any(Map.class));
        verify(auditService).log(eq("support_ticket"), eq(TICKET_ID), eq("SUPPORT_TICKET_RESOLVED"), eq(ADMIN_ID), any(Map.class));
    }

    @Test
    void assign_isIdempotentForTheSameAdmin() {
        SupportTicketEntity ticket = ticket(USER_ID, SupportTicketStatus.NEW);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));

        service.assign(TICKET_ID, ADMIN_ID);
        SupportTicketEntity again = service.assign(TICKET_ID, ADMIN_ID);

        assertThat(again.getAssignedAdminId()).isEqualTo(ADMIN_ID);
        assertThat(again.getStatus()).isEqualTo(SupportTicketStatus.ASSIGNED);
    }

    /** Deux admins ne doivent pas repondre en parallele : reprendre passe par reassign. */
    @Test
    void assign_rejectsTicketHeldByAnotherAdmin() {
        SupportTicketEntity ticket = ticket(USER_ID, SupportTicketStatus.ASSIGNED);
        ticket.setAssignedAdminId(OTHER_ADMIN_ID);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.assign(TICKET_ID, ADMIN_ID))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(SupportTicketServiceTest::statusOf)
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void assign_rejectsResolvedTicket() {
        SupportTicketEntity ticket = ticket(USER_ID, SupportTicketStatus.RESOLVED);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.assign(TICKET_ID, ADMIN_ID))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(SupportTicketServiceTest::statusOf)
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void reassign_transfersTicketAndTracesBothAdmins() {
        SupportTicketEntity ticket = ticket(USER_ID, SupportTicketStatus.ASSIGNED);
        ticket.setAssignedAdminId(OTHER_ADMIN_ID);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));

        SupportTicketEntity reassigned = service.reassign(TICKET_ID, ADMIN_ID, OTHER_ADMIN_ID);

        assertThat(reassigned.getAssignedAdminId()).isEqualTo(ADMIN_ID);
        verify(auditService).log(eq("support_ticket"), eq(TICKET_ID), eq("SUPPORT_TICKET_REASSIGNED"),
                eq(OTHER_ADMIN_ID), any(Map.class));
    }

    @Test
    void reassign_rejectsResolvedTicket() {
        SupportTicketEntity ticket = ticket(USER_ID, SupportTicketStatus.RESOLVED);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.reassign(TICKET_ID, ADMIN_ID, OTHER_ADMIN_ID))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(SupportTicketServiceTest::statusOf)
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void resolve_rejectsAlreadyResolvedTicket() {
        SupportTicketEntity ticket = ticket(USER_ID, SupportTicketStatus.RESOLVED);
        ticket.setAssignedAdminId(ADMIN_ID);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.resolve(TICKET_ID, ADMIN_ID))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(SupportTicketServiceTest::statusOf)
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void listReplies_returnsOnlyActiveRepliesInConfiguredOrder() {
        SupportPredefinedReplyEntity reply = new SupportPredefinedReplyEntity();
        reply.setCode("payment-refund");
        reply.setCategory("PAYMENT");
        reply.setQuestion("Quand arrive mon remboursement ?");
        reply.setAnswer("Les remboursements prennent en general 5 a 10 jours ouvres.");
        reply.setSortOrder(10);
        reply.setActive(true);
        when(replyRepository.findByActiveTrueOrderBySortOrderAsc()).thenReturn(List.of(reply));

        List<SupportPredefinedReplyEntity> replies = service.listActiveReplies();

        assertThat(replies).extracting(SupportPredefinedReplyEntity::getCode).containsExactly("payment-refund");
    }

    @Test
    void listUserTickets_isScopedToTheOwner() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<SupportTicketEntity> expected = Page.empty(pageable);
        when(ticketRepository.findByUserIdOrderByLastMessageAtDesc(USER_ID, pageable)).thenReturn(expected);

        assertThat(service.listUserTickets(USER_ID, pageable)).isSameAs(expected);
    }

    @Test
    void listAdminTickets_mapsEachScopeToItsQuery() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<SupportTicketEntity> empty = Page.empty(pageable);
        when(ticketRepository.findByAssignedAdminIdIsNullOrderByLastMessageAtDesc(pageable)).thenReturn(empty);
        when(ticketRepository.findByAssignedAdminIdAndStatusOrderByLastMessageAtDesc(
                ADMIN_ID, SupportTicketStatus.WAITING_SUPPORT, pageable)).thenReturn(empty);
        when(ticketRepository.findAllByOrderByLastMessageAtDesc(pageable)).thenReturn(empty);

        assertThat(service.listAdminTickets(SupportTicketScope.UNASSIGNED, null, ADMIN_ID, pageable)).isEmpty();
        assertThat(service.listAdminTickets(
                SupportTicketScope.MINE, SupportTicketStatus.WAITING_SUPPORT, ADMIN_ID, pageable)).isEmpty();
        assertThat(service.listAdminTickets(SupportTicketScope.ALL, null, ADMIN_ID, pageable)).isEmpty();
    }

    @Test
    void listAdminTickets_appliesStatusFilterOnUnassignedAndAllScopes() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<SupportTicketEntity> empty = Page.empty(pageable);
        when(ticketRepository.findByAssignedAdminIdIsNullAndStatusOrderByLastMessageAtDesc(
                SupportTicketStatus.NEW, pageable)).thenReturn(empty);
        when(ticketRepository.findByStatusOrderByLastMessageAtDesc(
                SupportTicketStatus.RESOLVED, pageable)).thenReturn(empty);
        when(ticketRepository.findByAssignedAdminIdOrderByLastMessageAtDesc(ADMIN_ID, pageable)).thenReturn(empty);

        assertThat(service.listAdminTickets(
                SupportTicketScope.UNASSIGNED, SupportTicketStatus.NEW, ADMIN_ID, pageable)).isEmpty();
        assertThat(service.listAdminTickets(
                SupportTicketScope.ALL, SupportTicketStatus.RESOLVED, ADMIN_ID, pageable)).isEmpty();
        assertThat(service.listAdminTickets(SupportTicketScope.MINE, null, ADMIN_ID, pageable)).isEmpty();
    }

    @Test
    void listMessages_delegatesToRepositoryInChronologicalOrder() {
        SupportMessageEntity message = new SupportMessageEntity();
        when(messageRepository.findByTicketIdOrderByCreatedAtAsc(TICKET_ID)).thenReturn(List.of(message));

        assertThat(service.listMessages(TICKET_ID)).containsExactly(message);
    }

    @Test
    void countUnassigned_delegatesToRepository() {
        when(ticketRepository.countByAssignedAdminIdIsNull()).thenReturn(7L);

        assertThat(service.countUnassigned()).isEqualTo(7L);
    }

    @Test
    void getTicketForAdmin_returnsTicketWithoutOwnershipCheck() {
        SupportTicketEntity ticket = ticket(OTHER_USER_ID, SupportTicketStatus.NEW);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));

        assertThat(service.getTicketForAdmin(TICKET_ID)).isSameAs(ticket);
    }

    private static HttpStatus statusOf(Throwable throwable) {
        return ((YadonyBusinessException) throwable).getStatus();
    }

    private static SupportTicketEntity ticket(UUID userId, SupportTicketStatus status) {
        SupportTicketEntity ticket = new SupportTicketEntity();
        ReflectionTestUtils.setField(ticket, "id", TICKET_ID);
        ticket.setUserId(userId);
        ticket.setCategory("PAYMENT");
        ticket.setSubject("Paiement bloque");
        ticket.setStatus(status);
        return ticket;
    }

    // ------------------------------------- conversation issue d'un signalement (contexte)

    @Test
    void adminStartTicketFromContext_postsTheContextSilentlyThenTheReplyWithThePush() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(USER_ID)));
        when(ticketRepository.save(any(SupportTicketEntity.class))).thenAnswer(inv -> {
            SupportTicketEntity t = inv.getArgument(0);
            ReflectionTestUtils.setField(t, "id", TICKET_ID);
            return t;
        });
        List<SupportMessageEntity> saved = new java.util.ArrayList<>();
        when(messageRepository.save(any(SupportMessageEntity.class))).thenAnswer(inv -> {
            SupportMessageEntity m = inv.getArgument(0);
            ReflectionTestUtils.setField(m, "id", UUID.randomUUID());
            saved.add(m);
            return m;
        });
        String adminPrefix = "support/admin/" + ADMIN_ID + "/";
        when(attachmentService.adminPrefix(ADMIN_ID)).thenReturn(adminPrefix);
        List<String> adminKeys = List.of(adminPrefix + "fix.png");
        when(attachmentService.requireOwnedKeys(adminKeys, adminPrefix)).thenReturn(adminKeys);
        List<String> sourceKeys = List.of("reports/" + USER_ID + "/a.png", "reports/" + USER_ID + "/b.jpg");
        List<String> copies = List.of("support/" + USER_ID + "/1.png", "support/" + USER_ID + "/2.jpg");
        when(attachmentService.copyIntoUserPrefix(USER_ID, sourceKeys)).thenReturn(copies);

        SupportTicketEntity ticket = service.adminStartTicketFromContext(USER_ID, ADMIN_ID, "OTHER",
                "Votre signalement du 28/09/2026", "Votre signalement : ...", sourceKeys,
                "  Merci, c'est corrige.  ", adminKeys);

        assertThat(ticket.getStatus()).isEqualTo(SupportTicketStatus.WAITING_USER);
        assertThat(ticket.getAssignedAdminId()).isEqualTo(ADMIN_ID);
        assertThat(ticket.getCategory()).isEqualTo("OTHER");

        assertThat(saved).hasSize(2);
        SupportMessageEntity context = saved.get(0);
        SupportMessageEntity reply = saved.get(1);
        assertThat(context.getContent()).isEqualTo("Votre signalement : ...");
        assertThat(context.getAuthorType()).isEqualTo(SupportMessageAuthorType.ADMIN);
        assertThat(context.getAuthorId()).isEqualTo(ADMIN_ID);
        assertThat(reply.getContent()).isEqualTo("Merci, c'est corrige.");
        assertThat(reply.getAuthorType()).isEqualTo(SupportMessageAuthorType.ADMIN);

        verify(attachmentService).attach(eq(context.getId()), eq(copies), eq("image/png"));
        verify(attachmentService).attach(eq(reply.getId()), eq(adminKeys), eq("image/png"));

        ArgumentCaptor<SupportMessageCreatedEvent> events = ArgumentCaptor.forClass(SupportMessageCreatedEvent.class);
        verify(eventPublisher, org.mockito.Mockito.times(2)).publishEvent(events.capture());
        assertThat(events.getAllValues().get(0).getMessageId()).isEqualTo(context.getId());
        assertThat(events.getAllValues().get(0).isNotifyOwner()).isFalse();
        assertThat(events.getAllValues().get(1).getMessageId()).isEqualTo(reply.getId());
        assertThat(events.getAllValues().get(1).isNotifyOwner()).isTrue();
        assertThat(events.getAllValues().get(1).isStartedByAdmin()).isTrue();
        assertThat(events.getAllValues().get(1).getSubject()).isEqualTo("Votre signalement du 28/09/2026");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("support_ticket"), eq(TICKET_ID), eq("SUPPORT_TICKET_ADMIN_STARTED"),
                eq(ADMIN_ID), details.capture());
        assertThat(details.getValue())
                .containsEntry("messageId", String.valueOf(reply.getId()))
                .containsEntry("contextMessageId", String.valueOf(context.getId()))
                .containsEntry("contextAttachmentCount", "2")
                .containsEntry("attachmentCount", "1");
        verifyNoInteractions(adminAlertService);
    }

    @Test
    void adminStartTicketFromContext_rejectsAnEmptyReply_beforeCopyingAnything() {
        assertThatThrownBy(() -> service.adminStartTicketFromContext(USER_ID, ADMIN_ID, "OTHER",
                "Sujet", "Contexte", List.of("reports/x.png"), "  ", null))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo("support-invalid-field"));
        verify(attachmentService, never()).copyIntoUserPrefix(any(), any());
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void adminStartTicketFromContext_unknownUser_is404_withoutCopy() {
        when(attachmentService.requireOwnedKeys(any(), any())).thenReturn(List.of());
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.adminStartTicketFromContext(USER_ID, ADMIN_ID, null,
                "Sujet", "Contexte", List.of("reports/x.png"), "Bonjour", null))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e ->
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(attachmentService, never()).copyIntoUserPrefix(any(), any());
    }

    @Test
    void adminReplyTakingOver_assignsAnUnassignedTicketThenReplies() {
        SupportTicketEntity ticket = ticketWith(SupportTicketStatus.NEW, null);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        when(messageRepository.save(any(SupportMessageEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(attachmentService.requireOwnedKeys(any(), any())).thenReturn(List.of());

        SupportMessageEntity message = service.adminReplyTakingOver(TICKET_ID, ADMIN_ID, "Bonjour", null);

        assertThat(message.getAuthorType()).isEqualTo(SupportMessageAuthorType.ADMIN);
        assertThat(ticket.getAssignedAdminId()).isEqualTo(ADMIN_ID);
        assertThat(ticket.getStatus()).isEqualTo(SupportTicketStatus.WAITING_USER);
        verify(auditService).log(eq("support_ticket"), eq(TICKET_ID), eq("SUPPORT_TICKET_ASSIGNED"), eq(ADMIN_ID), any());
    }

    @Test
    void adminReplyTakingOver_reassignsAColleaguesTicketThenReplies() {
        SupportTicketEntity ticket = ticketWith(SupportTicketStatus.WAITING_SUPPORT, OTHER_ADMIN_ID);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        when(messageRepository.save(any(SupportMessageEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(attachmentService.requireOwnedKeys(any(), any())).thenReturn(List.of());

        service.adminReplyTakingOver(TICKET_ID, ADMIN_ID, "Je reprends", null);

        assertThat(ticket.getAssignedAdminId()).isEqualTo(ADMIN_ID);
        verify(auditService).log(eq("support_ticket"), eq(TICKET_ID), eq("SUPPORT_TICKET_REASSIGNED"), eq(ADMIN_ID), any());
    }

    @Test
    void adminReplyTakingOver_onItsOwnTicket_justReplies() {
        SupportTicketEntity ticket = ticketWith(SupportTicketStatus.WAITING_SUPPORT, ADMIN_ID);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        when(messageRepository.save(any(SupportMessageEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(attachmentService.requireOwnedKeys(any(), any())).thenReturn(List.of());

        service.adminReplyTakingOver(TICKET_ID, ADMIN_ID, "Suite", null);

        verify(auditService, never()).log(any(), any(), eq("SUPPORT_TICKET_ASSIGNED"), any(), any());
        verify(auditService, never()).log(any(), any(), eq("SUPPORT_TICKET_REASSIGNED"), any(), any());
        verify(auditService).log(eq("support_ticket"), eq(TICKET_ID), eq("SUPPORT_TICKET_ADMIN_REPLIED"), eq(ADMIN_ID), any());
    }

    @Test
    void findTicketForAdmin_isEmptyForAnUnknownTicket() {
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.empty());

        assertThat(service.findTicketForAdmin(TICKET_ID)).isEmpty();
    }

    private static SupportTicketEntity ticketWith(SupportTicketStatus status, UUID assignedAdminId) {
        SupportTicketEntity ticket = new SupportTicketEntity();
        ReflectionTestUtils.setField(ticket, "id", TICKET_ID);
        ticket.setUserId(USER_ID);
        ticket.setCategory("OTHER");
        ticket.setSubject("Sujet");
        ticket.setStatus(status);
        ticket.setAssignedAdminId(assignedAdminId);
        return ticket;
    }

    private static UserEntity user(UUID id) {
        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", id);
        ReflectionTestUtils.setField(user, "firebaseUid", "firebase-" + id);
        ReflectionTestUtils.setField(user, "username", "user-" + id.toString().substring(0, 8));
        return user;
    }
}
