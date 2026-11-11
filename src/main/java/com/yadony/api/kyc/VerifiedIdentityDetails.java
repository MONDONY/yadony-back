package com.yadony.api.kyc;

import java.time.LocalDate;

/**
 * Identité lue sur la pièce vérifiée par le prestataire KYC : nom, date de naissance et adresse quand la pièce en porte
 * une. Seul usage : la déclaration DAC7 (obligation légale de la plateforme), lue à la demande au moment du calcul ou
 * de l'export.
 *
 * <p>Données personnelles : jamais journalisées ni persistées ; {@link #toString()} ne montre aucune valeur.
 *
 * @param countryCode code ISO alpha-2 du pays de l'adresse s'il est lisible, sinon {@code null}
 * @param freeAddress adresse en une ligne quand la pièce ne la détaille pas
 */
public record VerifiedIdentityDetails(String givenName, String surname, LocalDate birthDate, String street,
                                      String line2, String postalCode, String city, String countryCode,
                                      String freeAddress) {

    @Override
    public String toString() {
        return "VerifiedIdentityDetails[masqué]";
    }
}
