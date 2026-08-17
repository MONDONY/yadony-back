package com.yadony.api.matching.dto;

import jakarta.validation.constraints.Size;

public record ArrivalInstructionsRequest(
        @Size(max = 1000, message = "Les instructions de retrait ne peuvent pas dépasser 1000 caractères")
        String arrivalInstructions) {}
