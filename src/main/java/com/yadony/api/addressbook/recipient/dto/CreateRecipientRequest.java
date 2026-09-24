package com.yadony.api.addressbook.recipient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateRecipientRequest(
        @NotBlank @Size(max = 100) String fullName,
        @Size(max = 50) String relationship,
        @NotBlank @Pattern(regexp = "^\\+[1-9]\\d{1,14}$", message = "{validation.recipient.phone.e164}") String phoneE164,
        @Pattern(regexp = "^\\+[1-9]\\d{1,14}$", message = "{validation.recipient.whatsapp.e164}") String whatsappE164,
        @Size(max = 255) String street,
        @Size(max = 100) String city,
        @NotBlank @Pattern(regexp = "^(SN|CI|ML|CM)$", message = "{validation.recipient.country.allowed}") String country,
        String notes,
        boolean isDefault
) {}
