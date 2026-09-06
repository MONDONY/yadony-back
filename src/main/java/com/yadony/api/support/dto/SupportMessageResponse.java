package com.yadony.api.support.dto;

import com.yadony.api.support.SupportMessageEntity;

import java.time.LocalDateTime;
import java.util.UUID;

public record SupportMessageResponse(
        UUID id,
        String authorType,
        String content,
        LocalDateTime createdAt) {

    /**
     * {@code authorId} n'est volontairement pas expose : cote utilisateur il ne
     * sert a rien, et cote admin il revelerait l'identifiant du compte support
     * dans une reponse consommee par l'app.
     */
    public static SupportMessageResponse from(SupportMessageEntity entity) {
        return new SupportMessageResponse(
                entity.getId(),
                entity.getAuthorType().name(),
                entity.getContent(),
                entity.getCreatedAt());
    }
}
