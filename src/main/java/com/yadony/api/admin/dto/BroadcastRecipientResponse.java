package com.yadony.api.admin.dto;

import com.yadony.api.admin.broadcast.rule.RuleAudienceRepository.RecipientRow;

import java.time.Instant;
import java.util.UUID;

/** Un destinataire dans la liste d'aperçu : aucune coordonnée (ni téléphone, ni email). */
public record BroadcastRecipientResponse(UUID id, String displayName, String country, String language,
                                         boolean pushReachable, Instant signedUpAt) {

    public static BroadcastRecipientResponse from(RecipientRow row) {
        return new BroadcastRecipientResponse(row.userId(), row.displayName(), row.country(),
                row.language(), row.pushReachable(), row.signedUpAt());
    }
}
