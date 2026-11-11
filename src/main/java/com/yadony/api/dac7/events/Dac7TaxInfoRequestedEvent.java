package com.yadony.api.dac7.events;

import java.util.UUID;

/**
 * Demande (ou rappel) des informations fiscales à un voyageur, déjà inscrite au registre des diligences. Publiée dans
 * la transaction qui l'inscrit ; le paquet {@code notifications} l'envoie après validation (notification
 * {@code TAX_INFO_REQUEST}, route {@code /profile/tax-info}). Ne porte aucune donnée fiscale.
 *
 * @param action {@code REQUEST}, {@code REMINDER_1} ou {@code REMINDER_2}
 * @param scope  {@link #SCOPE_TAX_INFO} (informations fiscales d'un résident de l'Union) ou
 *               {@link #SCOPE_RESIDENCE_COUNTRY} (seulement le pays de résidence, inconnu)
 */
public record Dac7TaxInfoRequestedEvent(UUID userId, String action, String scope) {

    public static final String SCOPE_TAX_INFO = "TAX_INFO";
    public static final String SCOPE_RESIDENCE_COUNTRY = "RESIDENCE_COUNTRY";

    /** Demande ou rappel des informations fiscales (lot 3b). */
    public Dac7TaxInfoRequestedEvent(UUID userId, String action) {
        this(userId, action, SCOPE_TAX_INFO);
    }

    public boolean residenceCountryOnly() {
        return SCOPE_RESIDENCE_COUNTRY.equals(scope);
    }
}
