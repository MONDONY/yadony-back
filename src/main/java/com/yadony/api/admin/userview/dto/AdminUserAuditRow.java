package com.yadony.api.admin.userview.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Geste tracé sur un compte. {@code actorName} est l'e-mail de l'administrateur (les comptes admin n'ont
 * pas de nom) ou le nom de l'utilisateur pour une action automatique ; null si l'acteur est inconnu.
 * L'adresse IP du journal n'est jamais exposée.
 */
public record AdminUserAuditRow(
        long id,
        String action,
        String actorName,
        UUID actorId,
        String reason,
        Instant createdAt
) {}
