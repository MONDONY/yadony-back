package com.yadony.api.tracking.events;

import java.util.UUID;

/**
 * Publié par {@code TrackingService} quand le code de retrait est effacé après trop
 * d'essais faux du voyageur. L'expéditeur, seul à pouvoir en générer un nouveau
 * ({@code POST /tracking/{bidId}/refresh-code}), en est prévenu (FLUTTER-G1).
 */
public record ConfirmationCodeBlockedEvent(UUID bidId, UUID senderId) {}
