package com.yadony.api.dac7.dto;

import com.yadony.api.dac7.Dac7CollectionField;
import com.yadony.api.dac7.Dac7CollectionStatus;
import com.yadony.api.dac7.Dac7FieldSource;
import com.yadony.api.dac7.Dac7ResidencyStatus;
import com.yadony.api.dac7.Dac7SolicitationStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Informations fiscales vues par le voyageur lui-même. Le NIF et le numéro de TVA ne sortent jamais en clair : seuls
 * un indicateur de présence et une forme masquée ({@code ****} suivi d'au plus 4 derniers caractères, jamais plus que
 * la longueur moins 4). Champs nuls omis (NON_NULL) : un ancien client ignore les champs de la collecte ciblée.
 *
 * @param residenceCountry pays de résidence fiscale retenu (profil, ou pays de l'entreprise pour un professionnel)
 * @param dac7Status       déclarable, non déclarable ou résidence inconnue
 * @param solicitation     ancien contrat (lot 3d) : faut-il demander le NIF à un voyageur déjà payé
 * @param collectionStatus collecte ciblée (tâche 1) : quoi demander, quel que soit le paiement
 * @param missingFields    champs à demander, dans l'ordre d'affichage ; vide si rien à demander
 * @param readOnlyFields   champs repris de la pièce vérifiée (lecture seule ; l'adresse peut être remplacée)
 * @param actionRequired   vrai pour {@code RESIDENCE_INCONNUE} et {@code INFO_REQUIRED} : invite non bloquante
 * @param requestedAt      première demande faite au voyageur (à sa première acceptation de réservation ou par les
 *                         rappels), nulle s'il n'a encore rien reçu
 * @param identity         valeurs déjà connues avec leur source, seulement pour un particulier résident de l'Union
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
        Dac7SolicitationStatus solicitation,
        Dac7CollectionStatus collectionStatus,
        List<Dac7CollectionField> missingFields,
        List<Dac7CollectionField> readOnlyFields,
        Boolean actionRequired,
        Instant requestedAt,
        TaxIdentity identity
) {

    /**
     * Identité du voyageur pour l'écran fiscal. Ses propres données, à lui seul ; {@link #toString()} ne montre rien.
     *
     * @param addressReplaceable vrai quand l'adresse vient de la pièce : lien « Ce n'est plus mon adresse »
     * @param identityCheckUnavailable vrai quand la pièce n'a pas pu être relue (prestataire injoignable) : seules les
     *                                 valeurs du profil sont montrées, nul sinon
     */
    public record TaxIdentity(String firstName, String lastName, Dac7FieldSource nameSource, LocalDate birthDate,
                              Dac7FieldSource birthDateSource, TaxAddress address, Dac7FieldSource addressSource,
                              boolean addressReplaceable, boolean birthPlaceProvided,
                              Boolean identityCheckUnavailable) {

        @Override
        public String toString() {
            return "TaxIdentity[masked]";
        }
    }

    /** Adresse principale ; {@code country} est le pays de résidence. */
    public record TaxAddress(String street, String line2, String postalCode, String city, String country) {

        @Override
        public String toString() {
            return "TaxAddress[masked]";
        }
    }
}
