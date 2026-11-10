package com.yadony.api.videomodal.dto;

import com.yadony.api.videomodal.VideoModalEvent;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Événement de l'app. {@code occurredAt} est facultatif : il sert à la synchro des réponses
 * faites sans compte (une date future est ramenée à maintenant).
 */
public record VideoModalEventRequest(
        @NotNull @Min(1) Integer version,
        @NotNull VideoModalEvent event,
        Instant occurredAt) {
}
