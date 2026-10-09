package com.yadony.api.tracking.events;

import java.util.UUID;

/**
 * Publié par {@code TrackingService} quand le voyageur demande un nouveau code de retrait
 * ({@code POST /tracking/{bidId}/request-code}) : le code a été bloqué après trop d'essais
 * faux ou a expiré, et seul l'expéditeur peut en générer un nouveau
 * ({@code POST /tracking/{bidId}/refresh-code}). L'expéditeur en est prévenu (FLUTTER-G2).
 */
public record ConfirmationCodeRequestedEvent(UUID bidId, UUID senderId) {}
