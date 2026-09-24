package com.yadony.api.matching.dto;

import jakarta.validation.constraints.Size;

public record ArrivalInstructionsRequest(
        @Size(max = 1000, message = "{validation.trip.arrival-instructions.max}")
        String arrivalInstructions) {}
