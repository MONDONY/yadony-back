package com.yadony.api.admin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record AdminMaintenanceExemptionRequest(@NotNull UUID userId, @Size(max = 120) String note) {}
