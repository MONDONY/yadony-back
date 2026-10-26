package com.yadony.api.admin.dto;

import com.yadony.api.admin.dto.AdminReportContextResponse.HistoryEntry;
import com.yadony.api.support.SupportPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Corps et réponses des outils des agents du support (priorité, notes internes, réponses types). */
public final class AdminSupportToolsDtos {

    private AdminSupportToolsDtos() {}

    public record SetPriorityRequest(@NotNull SupportPriority priority) {}

    /** Les bornes (3 à 1000) sont vérifiées par le service, avec un code d'erreur stable. */
    public record AddNoteRequest(@NotBlank @Size(max = 2000) String content) {}

    public record TemplateRequest(@NotBlank @Size(max = 200) String label, @NotBlank @Size(max = 8000) String content) {}

    /** {@code adminEmail} : null si le compte de l'auteur n'existe plus. */
    public record NoteResponse(UUID id, String adminEmail, String content, LocalDateTime createdAt) {}

    public record TemplateResponse(UUID id, String label, String content, int sortOrder) {}

    /** Historique tracé du ticket (du plus récent au plus ancien) et ses notes internes (idem). */
    public record SupportContextResponse(List<HistoryEntry> history, List<NoteResponse> notes) {}
}
