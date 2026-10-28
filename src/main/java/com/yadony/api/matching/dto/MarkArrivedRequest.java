package com.yadony.api.matching.dto;

import com.yadony.api.tracking.dto.PositionRequest;
import jakarta.validation.constraints.Size;

/** Corps facultatif de {@code POST /announcements/{id}/mark-arrived}. */
public record MarkArrivedRequest(
        @Size(max = 1000, message = "{validation.trip.arrival-instructions.max}")
        String arrivalInstructions,
        // Pas de @Valid : rien dans le suivi ne doit bloquer une arrivée (position hors bornes ignorée).
        PositionRequest position) {}
