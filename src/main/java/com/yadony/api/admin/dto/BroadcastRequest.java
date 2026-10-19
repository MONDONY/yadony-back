package com.yadony.api.admin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Longueurs alignees sur les colonnes de admin_broadcasts (120 / 500).
 *
 * <p>{@code push} : faire aussi sonner les telephones, ou seulement deposer le message dans
 * la boite Annonces de l'app. Absent, il vaut {@code true} : c'etait le seul comportement
 * avant l'interrupteur, et une version de l'admin qui ne l'envoie pas encore le garde.
 */
public record BroadcastRequest(
        @NotBlank @Size(max = 120) String title,
        @NotBlank @Size(max = 500) String body,
        @NotNull @Valid BroadcastTargetRequest target,
        Boolean push) {

    /** Compatibilite : sans interrupteur, le broadcast part aussi en push. */
    public boolean pushOrDefault() {
        return push == null || push;
    }
}
