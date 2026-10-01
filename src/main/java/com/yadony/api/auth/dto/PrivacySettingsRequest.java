package com.yadony.api.auth.dto;

import jakarta.validation.constraints.NotNull;

/**
 * @param contactKycOnly seuls les profils vérifiés peuvent m'envoyer une offre.
 * @param hidePhoneNumber ne jamais révéler mon numéro à ma contrepartie. Nullable
 *        volontairement : une version de l'app antérieure à ce champ n'envoie que
 *        {@code contactKycOnly}, et un {@code null} laisse alors la préférence
 *        inchangée au lieu de la réinitialiser silencieusement.
 */
public record PrivacySettingsRequest(
        @NotNull Boolean contactKycOnly,
        Boolean hidePhoneNumber,
        /** Afficher mon pays de résidence sur mon profil public ; null = inchangé. */
        Boolean showResidenceCountry) {

    public PrivacySettingsRequest(Boolean contactKycOnly, Boolean hidePhoneNumber) {
        this(contactKycOnly, hidePhoneNumber, null);
    }
}
