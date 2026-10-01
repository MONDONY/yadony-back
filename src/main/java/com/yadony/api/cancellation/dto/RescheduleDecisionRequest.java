package com.yadony.api.cancellation.dto;

import com.yadony.api.cancellation.RescheduleDecision;
import jakarta.validation.constraints.NotNull;

public record RescheduleDecisionRequest(@NotNull RescheduleDecision decision) {}
