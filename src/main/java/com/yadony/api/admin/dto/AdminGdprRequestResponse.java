package com.yadony.api.admin.dto;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Une ligne de la file des demandes de suppression RGPD.
 *
 * <p>{@code ageDays} est calculé au rendu plutôt que stocké : c'est ce que l'administrateur
 * lit pour prioriser, et le délai de grâce (30 jours) est en dur côté back.
 *
 * <p>L'email vient de Firebase, jamais de la base : {@code public.users} ne stocke ni
 * téléphone ni email.
 */
public record AdminGdprRequestResponse(
        UUID id,
        String firstName,
        String lastName,
        String email,
        String status,
        LocalDateTime deletionRequestedAt,
        long ageDays
) {
    public static AdminGdprRequestResponse from(UserEntity u, FirebaseContactService.Contact contact) {
        Instant requestedAt = u.getDeletionRequestedAt();
        return new AdminGdprRequestResponse(
                u.getId(),
                u.getFirstName(),
                u.getLastName(),
                contact.email(),
                u.getStatus().name(),
                requestedAt != null ? LocalDateTime.ofInstant(requestedAt, ZoneOffset.UTC) : null,
                requestedAt != null ? ChronoUnit.DAYS.between(requestedAt, Instant.now()) : 0L
        );
    }
}
