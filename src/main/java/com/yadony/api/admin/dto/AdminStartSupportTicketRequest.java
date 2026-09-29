package com.yadony.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Conversation ouverte par le support vers un utilisateur. Bornes identiques a
 * {@code CreateSupportTicketRequest}, a une difference pres : la categorie est
 * facultative (OTHER par defaut). La regle « texte OU piece jointe » et la
 * validite de la categorie vivent dans le service, comme a la creation
 * utilisateur.
 */
public record AdminStartSupportTicketRequest(
        @NotNull UUID userId,
        @Size(max = 32) String category,
        @NotBlank @Size(max = 200) String subject,
        @Size(max = 4000) String message,
        List<String> attachmentKeys) {
}
