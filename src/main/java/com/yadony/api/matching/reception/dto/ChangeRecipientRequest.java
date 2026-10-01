package com.yadony.api.matching.reception.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Corps de {@code PUT /bids/{bidId}/recipient} : nouveau destinataire du colis. */
public record ChangeRecipientRequest(
        @NotBlank @Size(max = 100) String recipientName,
        @NotBlank @Pattern(regexp = "\\+[1-9]\\d{6,14}") String recipientPhone
) {}
