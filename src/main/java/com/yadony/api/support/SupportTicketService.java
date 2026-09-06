package com.yadony.api.support;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.stripe.AdminAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Workflow support : un ticket par probleme, jamais rouvert une fois resolu.
 *
 * <p>Deux referentiels d'acteurs cohabitent ici. L'utilisateur est une
 * {@link UserEntity} de la table {@code users} ; l'admin n'est qu'un
 * {@code UUID} issu de {@code admin_users} (via {@code AdminPrincipal}), que ce
 * service ne charge jamais — il n'a besoin que de l'identite pour l'assignation
 * et l'audit.
 */
@Service
@Transactional
public class SupportTicketService {

    private static final Logger log = LoggerFactory.getLogger(SupportTicketService.class);

    private static final String AUDIT_ENTITY = "support_ticket";
    private static final int MAX_SUBJECT_LENGTH = 200;
    private static final int MAX_MESSAGE_LENGTH = 4000;

    private final SupportTicketRepository ticketRepository;
    private final SupportMessageRepository messageRepository;
    private final SupportPredefinedReplyRepository replyRepository;
    private final AdminAlertService adminAlertService;
    private final AuditService auditService;

    public SupportTicketService(SupportTicketRepository ticketRepository,
                                SupportMessageRepository messageRepository,
                                SupportPredefinedReplyRepository replyRepository,
                                AdminAlertService adminAlertService,
                                AuditService auditService) {
        this.ticketRepository = ticketRepository;
        this.messageRepository = messageRepository;
        this.replyRepository = replyRepository;
        this.adminAlertService = adminAlertService;
        this.auditService = auditService;
    }

    // ---------------------------------------------------------------- lecture

    @Transactional(readOnly = true)
    public List<SupportPredefinedReplyEntity> listActiveReplies() {
        return replyRepository.findByActiveTrueOrderBySortOrderAsc();
    }

    @Transactional(readOnly = true)
    public Page<SupportTicketEntity> listUserTickets(UUID userId, Pageable pageable) {
        return ticketRepository.findByUserIdOrderByLastMessageAtDesc(userId, pageable);
    }

    @Transactional(readOnly = true)
    public SupportTicketEntity getUserTicket(UserEntity user, UUID ticketId) {
        return requireOwnedTicket(user, ticketId);
    }

    @Transactional(readOnly = true)
    public List<SupportMessageEntity> listMessages(UUID ticketId) {
        return messageRepository.findByTicketIdOrderByCreatedAtAsc(ticketId);
    }

    @Transactional(readOnly = true)
    public Page<SupportTicketEntity> listAdminTickets(SupportTicketScope scope,
                                                      SupportTicketStatus status,
                                                      UUID adminId,
                                                      Pageable pageable) {
        return switch (scope) {
            case UNASSIGNED -> status == null
                    ? ticketRepository.findByAssignedAdminIdIsNullOrderByLastMessageAtDesc(pageable)
                    : ticketRepository.findByAssignedAdminIdIsNullAndStatusOrderByLastMessageAtDesc(status, pageable);
            case MINE -> status == null
                    ? ticketRepository.findByAssignedAdminIdOrderByLastMessageAtDesc(adminId, pageable)
                    : ticketRepository.findByAssignedAdminIdAndStatusOrderByLastMessageAtDesc(adminId, status, pageable);
            case ALL -> status == null
                    ? ticketRepository.findAllByOrderByLastMessageAtDesc(pageable)
                    : ticketRepository.findByStatusOrderByLastMessageAtDesc(status, pageable);
        };
    }

    @Transactional(readOnly = true)
    public SupportTicketEntity getTicketForAdmin(UUID ticketId) {
        return requireTicket(ticketId);
    }

    @Transactional(readOnly = true)
    public long countUnassigned() {
        return ticketRepository.countByAssignedAdminIdIsNull();
    }

    // ------------------------------------------------------------- cote user

    public SupportTicketEntity createTicket(UserEntity user, String category, String subject, String firstMessage) {
        String normalizedCategory = normalizeCategory(category);
        String normalizedSubject = requireText(subject, "subject", MAX_SUBJECT_LENGTH);
        String normalizedMessage = requireText(firstMessage, "message", MAX_MESSAGE_LENGTH);

        SupportTicketEntity ticket = new SupportTicketEntity();
        ticket.setUserId(user.getId());
        ticket.setCategory(normalizedCategory);
        ticket.setSubject(normalizedSubject);
        ticket.setStatus(SupportTicketStatus.NEW);
        ticket.setPriority(SupportPriority.NORMAL);
        ticket.setLastMessageAt(now());
        SupportTicketEntity saved = ticketRepository.save(ticket);

        appendMessage(saved, SupportMessageAuthorType.USER, user.getId(), normalizedMessage);

        auditService.log(AUDIT_ENTITY, saved.getId(), "SUPPORT_TICKET_CREATED", user.getId(),
                payload("category", normalizedCategory, "status", saved.getStatus().name()));

        raiseCreationAlert(saved, normalizedSubject, normalizedCategory);
        return saved;
    }

    public SupportMessageEntity userReply(UserEntity user, UUID ticketId, String content) {
        SupportTicketEntity ticket = requireOwnedTicket(user, ticketId);
        if (ticket.isResolved()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "support-ticket-resolved", "Ticket resolu",
                    "Ce ticket est resolu. Ouvrez-en un nouveau pour un autre probleme.");
        }
        String normalized = requireText(content, "message", MAX_MESSAGE_LENGTH);

        SupportMessageEntity message =
                appendMessage(ticket, SupportMessageAuthorType.USER, user.getId(), normalized);
        ticket.setStatus(SupportTicketStatus.WAITING_SUPPORT);
        ticketRepository.save(ticket);
        return message;
    }

    // ------------------------------------------------------------ cote admin

    public SupportTicketEntity assign(UUID ticketId, UUID adminId) {
        SupportTicketEntity ticket = requireTicket(ticketId);
        requireNotResolved(ticket);
        UUID current = ticket.getAssignedAdminId();
        if (current != null && !current.equals(adminId)) {
            // Le point du garde-fou : deux admins ne doivent pas repondre en
            // parallele. Reprendre le ticket d'un collegue passe par /reassign,
            // qui exige SUPPORT_TICKET_MANAGE et laisse une trace distincte.
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "support-ticket-already-assigned", "Ticket deja assigne",
                    "Ce ticket est deja assigne a un autre administrateur.");
        }
        ticket.setAssignedAdminId(adminId);
        if (ticket.getStatus() == SupportTicketStatus.NEW) {
            ticket.setStatus(SupportTicketStatus.ASSIGNED);
        }
        ticketRepository.save(ticket);

        auditService.log(AUDIT_ENTITY, ticket.getId(), "SUPPORT_TICKET_ASSIGNED", adminId,
                payload("assignedAdminId", adminId.toString(), "status", ticket.getStatus().name()));
        return ticket;
    }

    public SupportTicketEntity reassign(UUID ticketId, UUID targetAdminId, UUID actorAdminId) {
        SupportTicketEntity ticket = requireTicket(ticketId);
        requireNotResolved(ticket);
        UUID previous = ticket.getAssignedAdminId();
        ticket.setAssignedAdminId(targetAdminId);
        if (ticket.getStatus() == SupportTicketStatus.NEW) {
            ticket.setStatus(SupportTicketStatus.ASSIGNED);
        }
        ticketRepository.save(ticket);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("previousAdminId", previous == null ? null : previous.toString());
        details.put("assignedAdminId", targetAdminId.toString());
        details.put("status", ticket.getStatus().name());
        auditService.log(AUDIT_ENTITY, ticket.getId(), "SUPPORT_TICKET_REASSIGNED", actorAdminId, details);
        return ticket;
    }

    public SupportMessageEntity adminReply(UUID ticketId, UUID adminId, String content) {
        SupportTicketEntity ticket = requireTicket(ticketId);
        requireAssignedTo(ticket, adminId);
        requireNotResolved(ticket);
        String normalized = requireText(content, "message", MAX_MESSAGE_LENGTH);

        SupportMessageEntity message =
                appendMessage(ticket, SupportMessageAuthorType.ADMIN, adminId, normalized);
        ticket.setStatus(SupportTicketStatus.WAITING_USER);
        ticketRepository.save(ticket);

        auditService.log(AUDIT_ENTITY, ticket.getId(), "SUPPORT_TICKET_ADMIN_REPLIED", adminId,
                payload("status", ticket.getStatus().name(), "messageId", String.valueOf(message.getId())));
        return message;
    }

    public SupportTicketEntity resolve(UUID ticketId, UUID adminId) {
        SupportTicketEntity ticket = requireTicket(ticketId);
        requireAssignedTo(ticket, adminId);
        requireNotResolved(ticket);

        ticket.setStatus(SupportTicketStatus.RESOLVED);
        ticket.setResolvedAt(now());
        ticketRepository.save(ticket);

        auditService.log(AUDIT_ENTITY, ticket.getId(), "SUPPORT_TICKET_RESOLVED", adminId,
                payload("status", ticket.getStatus().name(), "userId", String.valueOf(ticket.getUserId())));
        return ticket;
    }

    // ---------------------------------------------------------------- privees

    private SupportMessageEntity appendMessage(SupportTicketEntity ticket,
                                               SupportMessageAuthorType authorType,
                                               UUID authorId,
                                               String content) {
        SupportMessageEntity message = new SupportMessageEntity();
        message.setTicketId(ticket.getId());
        message.setAuthorType(authorType);
        message.setAuthorId(authorId);
        message.setContent(content);
        SupportMessageEntity saved = messageRepository.save(message);
        ticket.setLastMessageAt(now());
        return saved;
    }

    /**
     * L'alerte Telegram est hors du chemin critique : un bot injoignable ne doit
     * jamais empecher un utilisateur d'ouvrir un ticket.
     */
    private void raiseCreationAlert(SupportTicketEntity ticket, String subject, String category) {
        try {
            adminAlertService.raise(
                    "SUPPORT_TICKET_CREATED",
                    "Nouveau ticket support: " + subject,
                    payload("ticketId", String.valueOf(ticket.getId()),
                            "category", category,
                            "userId", String.valueOf(ticket.getUserId())));
        } catch (RuntimeException e) {
            log.warn("Alerte support non envoyee pour le ticket {}: {}", ticket.getId(), e.getMessage());
        }
    }

    private SupportTicketEntity requireTicket(UUID ticketId) {
        return ticketRepository.findById(ticketId)
                .orElseThrow(SupportTicketService::ticketNotFound);
    }

    /**
     * Un ticket appartenant a quelqu'un d'autre renvoie 404 et non 403 : un 403
     * confirmerait au demandeur que le ticket existe.
     */
    private SupportTicketEntity requireOwnedTicket(UserEntity user, UUID ticketId) {
        SupportTicketEntity ticket = requireTicket(ticketId);
        if (!ticket.getUserId().equals(user.getId())) {
            throw ticketNotFound();
        }
        return ticket;
    }

    private void requireAssignedTo(SupportTicketEntity ticket, UUID adminId) {
        if (!adminId.equals(ticket.getAssignedAdminId())) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT,
                    "support-ticket-not-assigned", "Ticket non assigne",
                    "Assignez-vous ce ticket avant d'y repondre ou de le resoudre.");
        }
    }

    private void requireNotResolved(SupportTicketEntity ticket) {
        if (ticket.isResolved()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "support-ticket-resolved", "Ticket resolu",
                    "Ce ticket est resolu et n'accepte plus d'action.");
        }
    }

    private static YadonyBusinessException ticketNotFound() {
        return new YadonyBusinessException(HttpStatus.NOT_FOUND,
                "support-ticket-not-found", "Ticket introuvable",
                "Ticket support introuvable");
    }

    private static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            throw invalidField("category", "La categorie est obligatoire");
        }
        String upper = category.trim().toUpperCase(Locale.ROOT);
        try {
            return SupportCategory.valueOf(upper).name();
        } catch (IllegalArgumentException e) {
            throw invalidField("category", "Categorie de support inconnue: " + category);
        }
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw invalidField(field, "Le champ " + field + " est obligatoire");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw invalidField(field, "Le champ " + field + " depasse " + maxLength + " caracteres");
        }
        return trimmed;
    }

    private static YadonyBusinessException invalidField(String field, String detail) {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                "support-invalid-field", "Champ invalide", detail,
                Map.of("field", field));
    }

    private static Map<String, Object> payload(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
