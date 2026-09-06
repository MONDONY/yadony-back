package com.yadony.api.support.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateSupportTicketRequest(
        @NotBlank @Size(max = 32) String category,
        @NotBlank @Size(max = 200) String subject,
        @Size(max = 4000) String message,
        List<String> attachmentKeys) {
}
