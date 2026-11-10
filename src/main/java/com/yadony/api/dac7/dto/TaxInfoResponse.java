package com.yadony.api.dac7.dto;

import com.yadony.api.dac7.Dac7ResidencyStatus;
import com.yadony.api.dac7.Dac7SolicitationStatus;

import java.time.Instant;

/**
 * Informations fiscales vues par le voyageur lui-même. Le NIF et le numéro de TVA ne sortent jamais en clair : seuls
 * un indicateur de présence et une forme masquée ({@code ****} suivi d'au plus 4 derniers caractères, jamais plus que
 * la longueur moins 4).
 *
 * @param residenceCountry pays de résidence fiscale retenu (profil, ou pays de l'entreprise pour un professionnel)
 * @param dac7Status       déclarable, non déclarable ou résidence inconnue
 * @param solicitation     faut-il demander le NIF : l'écran (lot 3d) ne s'affiche que pour {@code SOLLICITER}
 */
public record TaxInfoResponse(
        boolean provided,
        String maskedTaxIdentificationNumber,
        String taxCountry,
        boolean vatNumberProvided,
        String maskedVatNumber,
        Instant providedAt,
        String residenceCountry,
        Dac7ResidencyStatus dac7Status,
        Dac7SolicitationStatus solicitation
) {
}
