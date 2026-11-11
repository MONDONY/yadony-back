package com.yadony.api.dac7.dto;

/**
 * Informations fiscales saisies par le voyageur. Tous les champs sont facultatifs : seuls ceux envoyés sont modifiés
 * (un ancien client qui n'envoie que le NIF, son pays et la TVA garde exactement l'ancien comportement). Le contrôle
 * fin est fait par {@code TravelerTaxInfoService}, qui renvoie un 422 sans jamais recopier la valeur.
 *
 * @param taxIdentificationNumber numéro d'identification fiscale (NIF), 4 à 40 caractères : lettres, chiffres et
 *                                séparateurs (espace, point, tiret, barre oblique) ; NIF français : exactement 13
 *                                chiffres. Envoyé, il forme un bloc avec {@code taxCountry} (obligatoire) et
 *                                {@code vatNumber} (absent = effacé), comme dans l'ancien contrat
 * @param taxCountry              pays d'émission du NIF, code ISO 3166-1 alpha-2
 * @param vatNumber               numéro de TVA, facultatif ; sans NIF dans la même requête, {@code ""} l'efface
 * @param firstName               prénom, seulement s'il ne vient pas de la pièce vérifiée (sinon il doit être égal)
 * @param lastName                nom, mêmes règles
 * @param birthDate               date de naissance {@code AAAA-MM-JJ}, seulement si elle ne vient pas de la pièce
 *                                (sinon elle doit être égale)
 * @param birthPlace              lieu de naissance, seulement sans NIF ({@code ""} l'efface) ; effacé dès qu'un NIF est
 *                                enregistré
 * @param address                 adresse principale complète ; remplace aussi une adresse reprise de la pièce
 *                                (« Ce n'est plus mon adresse »). Le pays est celui de résidence
 */
public record TaxInfoRequest(String taxIdentificationNumber, String taxCountry, String vatNumber, String firstName,
                             String lastName, String birthDate, String birthPlace, AddressInput address) {

    /** Ancien contrat (lot 3a) : NIF, pays d'émission et TVA. */
    public TaxInfoRequest(String taxIdentificationNumber, String taxCountry, String vatNumber) {
        this(taxIdentificationNumber, taxCountry, vatNumber, null, null, null, null, null);
    }

    /** Au moins un champ d'identité (nom, naissance, adresse) est envoyé. */
    public boolean hasIdentityFields() {
        return firstName != null || lastName != null || birthDate != null || birthPlace != null || address != null;
    }

    /**
     * Adresse principale saisie : rue, code postal et ville obligatoires, complément facultatif.
     */
    public record AddressInput(String street, String line2, String postalCode, String city) {

        @Override
        public String toString() {
            return "AddressInput[masked]";
        }
    }

    /** Jamais les valeurs en clair, même dans un journal de débogage. */
    @Override
    public String toString() {
        return "TaxInfoRequest[masked]";
    }
}
