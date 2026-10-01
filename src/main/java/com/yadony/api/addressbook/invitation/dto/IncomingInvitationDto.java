package com.yadony.api.addressbook.invitation.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/** Invitation reçue, vue par l'invité. */
public record IncomingInvitationDto(UUID id, String inviterFirstName, String status, LocalDateTime createdAt) {}
