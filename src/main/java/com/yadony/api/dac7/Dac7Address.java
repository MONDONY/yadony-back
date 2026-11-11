package com.yadony.api.dac7;

/**
 * Adresse d'un vendeur pour l'export DAC7 : détaillée (rue, code postal, ville) quand la ville est connue, sinon en une
 * ligne ({@code free}). {@link #toString()} ne montre aucune valeur.
 *
 * @param countryCode code ISO alpha-2 du pays de l'adresse
 */
public record Dac7Address(String street, String line2, String postCode, String city, String countryCode,
                          String free) {

    /** Utilisable dans le XML : un pays et soit une ville (forme détaillée), soit une ligne libre. */
    public boolean usable() {
        return countryCode != null && (notBlank(city) || notBlank(free));
    }

    public boolean detailed() {
        return notBlank(city);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public String toString() {
        return "Dac7Address[masqué]";
    }
}
