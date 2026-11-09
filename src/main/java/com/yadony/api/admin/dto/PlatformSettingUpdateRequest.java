package com.yadony.api.admin.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Nouvelle valeur d'un reglage, en texte.
 *
 * <p>Les bornes ne sont volontairement PAS declarees ici en annotations : elles dependent de
 * la cle et vivent dans {@code PlatformSettingsService.normalize} — une seule regle, un seul
 * endroit, et le meme 422 RFC 7807 quel que soit le chemin d'appel.
 *
 * <p>{@code @NotNull} et non {@code @NotBlank} : une liste d'indicatifs vide
 * ({@code sms_blocked_country_codes}) veut dire « aucun pays bloqué ». Le vide des autres
 * reglages est refuse par le controleur, avant le service.
 */
public record PlatformSettingUpdateRequest(
        @NotNull(message = "La valeur du reglage est obligatoire") String value) {
}
