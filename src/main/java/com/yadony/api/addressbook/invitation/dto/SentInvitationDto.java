package com.yadony.api.addressbook.invitation.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Invitation envoyée, vue par l'inviteur. {@code status} vaut {@code PENDING} pour toute
 * invitation non acceptée (refusée ou sans compte comprises) : on ne distingue jamais.
 */
public record SentInvitationDto(UUID id, String channel, String maskedTarget, String status,
                                LocalDateTime createdAt) {}
