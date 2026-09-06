package com.yadony.api.support;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.stripe.AdminAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

    private SupportTicketService service;

    @BeforeEach
    void setUp() {
        service = new SupportTicketService(
                ticketRepository,
                messageRepository,
                replyRepository,
                adminAlertService,
                auditService);
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

        SupportTicketEntity ticket = service.createTicket(
                user,
                "PAYMENT",
                "Paiement bloque",
                "Je ne vois pas le remboursement.");

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

        SupportTicketEntity ticket = service.createTicket(
                user, "payment", "Paiement bloque", "Je ne vois pas le remboursement.");

        assertThat(ticket.getId()).isEqualTo(TICKET_ID);
    }

    @Test
    void createTicket_rejectsUnknownCategory() {
        assertThatThrownBy(() -> service.createTicket(user(USER_ID), "NOPE", "Sujet", "Message"))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(SupportTicketServiceTest::statusOf)
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        verifyNoInteractions(ticketRepository, messageRepository);
    }

    @Test
    void createTicket_rejectsBlankSubject() {
        assertThatThrownBy(() -> service.createTicket(user(USER_ID), "PAYMENT", "   ", "Message"))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(SupportTicketServiceTest::statusOf)
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void createTicket_rejectsOverlongMessage() {
        String tooLong = "x".repeat(4001);
        assertThatThrownBy(() -> service.createTicket(user(USER_ID), "PAYMENT", "Sujet", tooLong))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(SupportTicketServiceTest::statusOf)
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void userReply_appendsMessageAndWaitsForSupport() {
        SupportTicketEntity ticket = ticket(USER_ID, SupportTicketStatus.WAITING_USER);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        when(messageRepository.save(any(SupportMessageEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        SupportMessageEntity message = service.userReply(user(USER_ID), TICKET_ID, "  Toujours bloque  ");

        assertThat(message.getAuthorType()).isEqualTo(SupportMessageAuthorType.USER);
        assertThat(message.getAuthorId()).isEqualTo(USER_ID);
        assertThat(message.getContent()).isEqualTo("Toujours bloque");
        assertThat(ticket.getStatus()).isEqualTo(SupportTicketStatus.WAITING_SUPPORT);
    }

    @Test
    void userReply_rejectsResolvedTicket() {
        SupportTicketEntity ticket = ticket(USER_ID, SupportTicketStatus.RESOLVED);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.userReply(user(USER_ID), TICKET_ID, "Toujours bloque"))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(SupportTicketServiceTest::statusOf)
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    /** 404 et non 403 : un 403 confirmerait au demandeur que le ticket existe. */
    @Test
    void userReply_rejectsTicketOwnedByAnotherUser() {
        SupportTicketEntity ticket = ticket(OTHER_USER_ID, SupportTicketStatus.WAITING_USER);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.userReply(user(USER_ID), TICKET_ID, "Bonjour"))
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

        assertThatThrownBy(() -> service.adminReply(TICKET_ID, ADMIN_ID, "Je regarde."))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(SupportTicketServiceTest::statusOf)
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void adminReply_requiresAnyAssignment() {
        SupportTicketEntity ticket = ticket(USER_ID, SupportTicketStatus.NEW);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> service.adminReply(TICKET_ID, ADMIN_ID, "Je regarde."))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(SupportTicketServiceTest::statusOf)
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void assignThenAdminReplyThenResolve_movesTicketThroughSupportFlow() {
        SupportTicketEntity ticket = ticket(USER_ID, SupportTicketStatus.NEW);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        when(messageRepository.save(any(SupportMessageEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        SupportTicketEntity assigned = service.assign(TICKET_ID, ADMIN_ID);
        SupportMessageEntity reply = service.adminReply(TICKET_ID, ADMIN_ID, "On verifie le paiement.");
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

    private static UserEntity user(UUID id) {
        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", id);
        ReflectionTestUtils.setField(user, "firebaseUid", "firebase-" + id);
        ReflectionTestUtils.setField(user, "username", "user-" + id.toString().substring(0, 8));
        return user;
    }
}
