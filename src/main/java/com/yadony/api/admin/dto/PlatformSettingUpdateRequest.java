package com.yadony.api.admin.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Nouvelle valeur d'un reglage, en texte.
 *
 * <p>Les bornes ne sont volontairement PAS declarees ici en annotations : elles dependent de
 * la cle et vivent dans {@code PlatformSettingsService.normalize} — une seule regle, un seul
 * endroit, et le meme 422 RFC 7807 quel que soit le chemin d'appel.
 */
public record PlatformSettingUpdateRequest(
        @NotBlank(message = "La valeur du reglage est obligatoire") String value) {
}
