package com.yadony.api.dac7.dto;

/**
 * Informations fiscales saisies par le voyageur. Le contrôle fin (longueur, caractères, code pays) est fait par
 * {@code TravelerTaxInfoService}, qui renvoie un 422 {@code tax-info-invalid} sans jamais recopier la valeur.
 *
 * @param taxIdentificationNumber numéro d'identification fiscale (NIF), 4 à 40 caractères : lettres, chiffres et
 *                                séparateurs (espace, point, tiret, barre oblique)
 * @param taxCountry              pays d'émission du NIF, code ISO 3166-1 alpha-2
 * @param vatNumber               numéro de TVA, facultatif (vide = absent)
 */
public record TaxInfoRequest(String taxIdentificationNumber, String taxCountry, String vatNumber) {

    /** Jamais les valeurs en clair, même dans un journal de débogage. */
    @Override
    public String toString() {
        return "TaxInfoRequest[masked]";
    }
}
