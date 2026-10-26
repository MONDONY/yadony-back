package com.yadony.api.support;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Outils des agents du support : notes internes sur un ticket et réponses types. Les gestes sont
 * tracés dans l'audit ; une réponse type supprimée l'est en soft delete.
 */
@Service
@Transactional
public class SupportAdminToolsService {

    static final String AUDIT_TICKET = "support_ticket";
    static final String AUDIT_TEMPLATE = "support_reply_template";
    static final int NOTE_MIN = 3;
    static final int NOTE_MAX = 1000;
    static final int LABEL_MAX = 60;
    static final int CONTENT_MAX = 4000;

    private final SupportTicketRepository ticketRepository;
    private final SupportTicketNoteRepository noteRepository;
    private final SupportReplyTemplateRepository templateRepository;
    private final AuditService auditService;

    public SupportAdminToolsService(SupportTicketRepository ticketRepository,
                                    SupportTicketNoteRepository noteRepository,
                                    SupportReplyTemplateRepository templateRepository,
                                    AuditService auditService) {
        this.ticketRepository = ticketRepository;
        this.noteRepository = noteRepository;
        this.templateRepository = templateRepository;
        this.auditService = auditService;
    }

    // ------------------------------------------------------------------ notes

    @Transactional(readOnly = true)
    public List<SupportTicketNoteEntity> listNotes(UUID ticketId) {
        requireTicket(ticketId);
        return noteRepository.findByTicketIdOrderByCreatedAtDesc(ticketId);
    }

    public SupportTicketNoteEntity addNote(UUID ticketId, UUID adminId, String content) {
        requireTicket(ticketId);
        String text = content == null ? "" : content.strip();
        if (text.length() < NOTE_MIN || text.length() > NOTE_MAX) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "support-note-invalid", "Note invalide",
                    "La note doit faire entre " + NOTE_MIN + " et " + NOTE_MAX + " caracteres");
        }
        SupportTicketNoteEntity note = new SupportTicketNoteEntity();
        note.setTicketId(ticketId);
        note.setAdminId(adminId);
        note.setContent(text);
        SupportTicketNoteEntity saved = noteRepository.save(note);
        // Le texte reste hors de l'audit (il est dans la table) : la trace dit qui et quand.
        auditService.log(AUDIT_TICKET, ticketId, "SUPPORT_TICKET_NOTE_ADDED", adminId,
                Map.of("noteId", String.valueOf(saved.getId())));
        return saved;
    }

    // ------------------------------------------------------- réponses types

    @Transactional(readOnly = true)
    public List<SupportReplyTemplateEntity> listTemplates() {
        return templateRepository.findAllByOrderBySortOrderAscCreatedAtAsc();
    }

    public SupportReplyTemplateEntity createTemplate(UUID adminId, String label, String content) {
        SupportReplyTemplateEntity template = new SupportReplyTemplateEntity();
        apply(template, label, content);
        template.setSortOrder(listTemplates().stream().mapToInt(SupportReplyTemplateEntity::getSortOrder).max().orElse(0) + 10);
        SupportReplyTemplateEntity saved = templateRepository.save(template);
        auditService.log(AUDIT_TEMPLATE, saved.getId(), "SUPPORT_REPLY_TEMPLATE_CREATED", adminId,
                Map.of("label", saved.getLabel()));
        return saved;
    }

    public SupportReplyTemplateEntity updateTemplate(UUID templateId, UUID adminId, String label, String content) {
        SupportReplyTemplateEntity template = requireTemplate(templateId);
        apply(template, label, content);
        SupportReplyTemplateEntity saved = templateRepository.save(template);
        auditService.log(AUDIT_TEMPLATE, saved.getId(), "SUPPORT_REPLY_TEMPLATE_UPDATED", adminId,
                Map.of("label", saved.getLabel()));
        return saved;
    }

    /** Soft delete : la réponse type disparaît de la liste, sa ligne reste. */
    public void deleteTemplate(UUID templateId, UUID adminId) {
        SupportReplyTemplateEntity template = requireTemplate(templateId);
        template.setDeletedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC));
        templateRepository.save(template);
        auditService.log(AUDIT_TEMPLATE, template.getId(), "SUPPORT_REPLY_TEMPLATE_DELETED", adminId,
                Map.of("label", template.getLabel()));
    }

    private static void apply(SupportReplyTemplateEntity template, String label, String content) {
        String l = label == null ? "" : label.strip();
        String c = content == null ? "" : content.strip();
        if (l.isEmpty() || l.length() > LABEL_MAX || c.isEmpty() || c.length() > CONTENT_MAX) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "support-template-invalid", "Reponse type invalide",
                    "Le libelle (" + LABEL_MAX + " caracteres au plus) et le texte (" + CONTENT_MAX
                            + " caracteres au plus) sont obligatoires");
        }
        template.setLabel(l);
        template.setContent(c);
    }

    private SupportTicketEntity requireTicket(UUID ticketId) {
        return ticketRepository.findById(ticketId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "support-ticket-not-found", "Ticket introuvable", "Aucun ticket ne correspond"));
    }

    private SupportReplyTemplateEntity requireTemplate(UUID templateId) {
        return templateRepository.findById(templateId)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "support-template-not-found", "Reponse type introuvable", "Aucune reponse type ne correspond"));
    }
}
