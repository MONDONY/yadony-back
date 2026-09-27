package com.yadony.api.admin.dto;

import com.yadony.api.matching.AnnouncementRemovalReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Retrait d'une demande d'envoi par la modération. Même séparation que
 * {@link RemoveAnnouncementRequest} : {@code publicReason} (catalogue commun aux annonces)
 * est annoncé à l'expéditeur, {@code internalNote} ne quitte jamais {@code audit_log}.
 */
public record RemovePackageRequestRequest(
        @NotNull(message = "Le motif de retrait est obligatoire")
        AnnouncementRemovalReason publicReason,

        @Size(max = 500, message = "La note interne ne peut pas dépasser 500 caractères")
        String internalNote
) {}
