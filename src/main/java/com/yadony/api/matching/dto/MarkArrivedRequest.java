package com.yadony.api.matching.dto;

import com.yadony.api.tracking.dto.PositionRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

/** Corps facultatif de {@code POST /announcements/{id}/mark-arrived}. */
public record MarkArrivedRequest(
        @Size(max = 1000, message = "{validation.trip.arrival-instructions.max}")
        String arrivalInstructions,
        @Valid PositionRequest position) {}
