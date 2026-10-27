package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminUserEntity;
import com.yadony.api.admin.account.AdminUserRepository;
import com.yadony.api.admin.dto.AdminConversationToolsDtos.ConversationContextResponse;
import com.yadony.api.admin.dto.AdminConversationToolsDtos.LinkedReport;
import com.yadony.api.admin.dto.AdminConversationToolsDtos.NoteResponse;
import com.yadony.api.admin.dto.AdminConversationToolsDtos.Participant;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.MatchingTextUtil;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.messaging.ConversationEntity;
import com.yadony.api.signalements.ReportEntity;
import com.yadony.api.signalements.ReportRepository;
import com.yadony.api.signalements.ReportTargetType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Outils de la modération d'une conversation : son contexte (historique, notes, signalements,
 * participants) et les notes internes. Les gestes sur les messages (suppression, restauration) sont
 * dans {@link AdminMessageModerationService} et {@link AdminConversationController}.
 */
@Service
public class AdminConversationToolsService {

    static final int NOTE_MIN = 3;
    static final int NOTE_MAX = 1000;
    static final int REPORTS_LIMIT = 20;
    /** Même type d'entité que les suppressions et restaurations : un seul historique par conversation. */
    static final String AUDIT_ENTITY = "message";

    private final ConversationAdminNoteRepository notes;
    private final ReportContextService reportContext;
    private final ReportRepository reports;
    private final UserRepository users;
    private final AdminUserRepository admins;
    private final AuditService auditService;

    public AdminConversationToolsService(ConversationAdminNoteRepository notes, ReportContextService reportContext,
                                         ReportRepository reports, UserRepository users, AdminUserRepository admins,
                                         AuditService auditService) {
        this.notes = notes;
        this.reportContext = reportContext;
        this.reports = reports;
        this.users = users;
        this.admins = admins;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public ConversationContextResponse context(ConversationEntity conversation) {
        UUID id = conversation.getId();
        List<ConversationAdminNoteEntity> noteRows = notes.findByConversationIdOrderByCreatedAtDesc(id);
        List<ReportEntity> reportRows = reports.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(ReportTargetType.MESSAGE, id)
                .stream().limit(REPORTS_LIMIT).toList();

        Map<UUID, String> adminEmails = new HashMap<>();
        for (AdminUserEntity admin : admins.findAllById(noteRows.stream()
                .map(ConversationAdminNoteEntity::getAdminId).collect(Collectors.toSet()))) {
            adminEmails.put(admin.getId(), admin.getEmail());
        }
        List<UUID> userIds = new ArrayList<>();
        userIds.add(conversation.participantAId());
        userIds.add(conversation.getTravelerId());
        reportRows.stream().map(ReportEntity::getReporterId).filter(java.util.Objects::nonNull).forEach(userIds::add);
        Map<UUID, UserEntity> byId = new HashMap<>();
        for (UserEntity u : users.findAllById(userIds.stream().filter(java.util.Objects::nonNull).distinct().toList())) {
            byId.put(u.getId(), u);
        }

        List<Participant> participants = new ArrayList<>();
        participants.add(participant(conversation.participantAId(), "A", byId));
        participants.add(participant(conversation.getTravelerId(), "B", byId));
        return new ConversationContextResponse(
                reportContext.history(AUDIT_ENTITY, id),
                noteRows.stream().map(n -> new NoteResponse(n.getId(), adminEmails.get(n.getAdminId()),
                        n.getContent(), n.getCreatedAt())).toList(),
                reportRows.stream().map(r -> new LinkedReport(r.getId(),
                        r.getReason() != null ? r.getReason().name() : null,
                        r.getStatus() != null ? r.getStatus().name() : null,
                        r.getReporterId() != null && byId.containsKey(r.getReporterId())
                                ? MatchingTextUtil.buildName(byId.get(r.getReporterId())) : null,
                        r.getCreatedAt())).toList(),
                participants);
    }

    private static Participant participant(UUID userId, String role, Map<UUID, UserEntity> byId) {
        UserEntity u = userId == null ? null : byId.get(userId);
        return new Participant(userId, u != null ? MatchingTextUtil.buildName(u) : null, role);
    }

    @Transactional
    public ConversationAdminNoteEntity addNote(ConversationEntity conversation, UUID adminId, String content) {
        String text = content == null ? "" : content.strip();
        if (text.length() < NOTE_MIN || text.length() > NOTE_MAX) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "conversation-note-invalid", "Note invalide",
                    "La note doit faire entre " + NOTE_MIN + " et " + NOTE_MAX + " caracteres");
        }
        ConversationAdminNoteEntity note = new ConversationAdminNoteEntity();
        note.setConversationId(conversation.getId());
        note.setAdminId(adminId);
        note.setContent(text);
        ConversationAdminNoteEntity saved = notes.save(note);
        // Le texte reste hors de l'audit (il est dans la table) : la trace dit qui et quand.
        auditService.log(AUDIT_ENTITY, conversation.getId(), "CONVERSATION_NOTE_ADDED", adminId,
                Map.of("conversationId", String.valueOf(conversation.getFirestoreConversationId()),
                        "noteId", String.valueOf(saved.getId())));
        return saved;
    }
}
