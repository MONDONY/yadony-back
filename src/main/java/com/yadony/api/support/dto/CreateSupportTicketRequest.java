package com.yadony.api.support.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSupportTicketRequest(
        @NotBlank @Size(max = 32) String category,
        @NotBlank @Size(max = 200) String subject,
        @NotBlank @Size(max = 4000) String message) {
}
