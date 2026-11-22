package com.yadony.api.matching.reception.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Corps de {@code PUT /bids/{bidId}/recipient} : nouveau destinataire du colis, désigné soit par nom + numéro,
 * soit par l'identifiant d'un compte Yadony (jamais les deux). L'exclusivité et la présence d'un destinataire sont
 * vérifiées par le service ({@code recipient-mode-conflict}, {@code recipient-required}).
 */
public record ChangeRecipientRequest(
        @Size(max = 100) String recipientName,
        @Pattern(regexp = "(\\+[1-9]\\d{6,14})?") String recipientPhone,
        @Size(max = 64) String recipientUsername
) {
    /** Forme d'avant la désignation par identifiant : nom + numéro. */
    public ChangeRecipientRequest(String recipientName, String recipientPhone) {
        this(recipientName, recipientPhone, null);
    }
}
