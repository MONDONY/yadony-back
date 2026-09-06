package com.yadony.api.support.dto;

import com.yadony.api.support.SupportMessageEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record SupportMessageResponse(
        UUID id,
        String authorType,
        String content,
        LocalDateTime createdAt,
        List<SupportAttachmentResponse> attachments) {

    /**
     * {@code authorId} n'est volontairement pas expose : cote utilisateur il ne
     * sert a rien, et cote admin il revelerait l'identifiant du compte support
     * dans une reponse consommee par l'app.
     *
     * <p>La fabrique a un argument a DISPARU : tous les appelants doivent passer
     * la liste des pieces jointes (vide si aucune).
     */
    public static SupportMessageResponse from(SupportMessageEntity entity,
                                              List<SupportAttachmentResponse> attachments) {
        return new SupportMessageResponse(
                entity.getId(),
                entity.getAuthorType().name(),
                entity.getContent(),
                entity.getCreatedAt(),
                attachments == null ? List.of() : attachments);
    }
}
