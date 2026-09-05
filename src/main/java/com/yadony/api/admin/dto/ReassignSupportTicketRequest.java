package com.yadony.api.admin.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ReassignSupportTicketRequest(@NotNull UUID adminId) {
}
