package com.yadony.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Lot C — exécution administrateur d'une suppression RGPD. <strong>Irréversible.</strong>
 *
 * <p>{@code withoutUserRequest} est l'aveu explicite qu'aucune demande n'existe en base : une
 * demande RGPD arrive légitimement par courrier ou par mail, hors application. Absent du
 * corps, il vaut {@code false} — le cas nominal, celui de la file des demandes. Le poser à
 * {@code true} reste un geste délibéré, consigné dans {@code audit_log}.
 */
public record GdprExecuteRequest(
        @NotBlank(message = "Le motif est obligatoire")
        @Size(max = 500) String reason,

        Boolean withoutUserRequest
) {

    /** {@code null} (champ absent du JSON) vaut {@code false} : jamais d'aveu par omission. */
    public boolean withoutUserRequestOrFalse() {
        return Boolean.TRUE.equals(withoutUserRequest);
    }
}
