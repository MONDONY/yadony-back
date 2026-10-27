package com.yadony.api.admin.dto;

import com.yadony.api.admin.dto.AdminReportContextResponse.HistoryEntry;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Corps et réponses des outils de la modération des conversations (contexte, notes internes). */
public final class AdminConversationToolsDtos {

    private AdminConversationToolsDtos() {}

    /** Les bornes (3 à 1000) sont vérifiées par le service, avec un code d'erreur stable. */
    public record AddNoteRequest(@NotBlank @Size(max = 2000) String content) {}

    /** {@code adminEmail} : null si le compte de l'auteur n'existe plus. */
    public record NoteResponse(UUID id, String adminEmail, String content, LocalDateTime createdAt) {}

    /** Un des deux comptes de la conversation ; {@code role} : A (expéditeur ou destinataire) ou B (voyageur). */
    public record Participant(UUID userId, String displayName, String role) {}

    /** Un signalement sur un message de la conversation, tous statuts confondus. */
    public record LinkedReport(UUID id, String reason, String status, String reporterName, LocalDateTime createdAt) {}

    /**
     * Contexte d'une conversation : historique des gestes de modération (du plus récent au plus
     * ancien, motif de suppression compris), notes internes, signalements liés et participants.
     */
    public record ConversationContextResponse(
            List<HistoryEntry> history,
            List<NoteResponse> notes,
            List<LinkedReport> reports,
            List<Participant> participants
    ) {}
}
