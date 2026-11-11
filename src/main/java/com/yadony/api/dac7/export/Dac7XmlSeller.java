package com.yadony.api.dac7.export;

import java.time.LocalDate;
import java.util.List;

/**
 * Un vendeur à déclarer, tel qu'il part dans le XML : valeurs EN CLAIR (NIF compris), le temps de la génération
 * seulement. {@link #toString()} ne montre aucune valeur.
 *
 * @param entity          vrai pour une entité ({@code EntitySeller}), faux pour une personne physique
 * @param resCountryCode  État de résidence (code accepté par la DGFiP : un DOM est {@code FR})
 * @param tin             numéro d'identification fiscale
 * @param tinIssuedBy     pays d'émission du NIF
 * @param quarters        quatre trimestres, dans l'ordre, montants en euros entiers
 */
public record Dac7XmlSeller(boolean entity, String resCountryCode, String tin, String tinIssuedBy, String vat,
                            String firstName, String lastName, LocalDate birthDate, String businessName,
                            String registrationNumber, Address address, List<Quarter> quarters) {

    /**
     * Adresse détaillée ({@code city} non nul) ou libre ({@code free}). Le champ {@code building} est gardé pour la
     * forme mais n'est plus écrit : un complément d'adresse part dans la zone libre (voir {@code Dac7ExportService}).
     */
    public record Address(String countryCode, String street, String building, String postCode, String city,
                          String free) {
        @Override
        public String toString() {
            return "Address[masqué]";
        }
    }

    /** Montants en euros entiers (arrondis à l'export seulement) et nombre d'opérations. */
    public record Quarter(long consideration, int operations, long fees) {
    }

    @Override
    public String toString() {
        return "Dac7XmlSeller[masqué]";
    }
}
