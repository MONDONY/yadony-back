package com.yadony.api.cancellation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/** Nouveau rendez-vous de livraison fixé par l'expéditeur pendant la garde (FLUTTER-E2). */
public record RetryAppointmentRequest(
        @NotNull OffsetDateTime appointmentAt,
        @Size(max = 500) String note
) {}
