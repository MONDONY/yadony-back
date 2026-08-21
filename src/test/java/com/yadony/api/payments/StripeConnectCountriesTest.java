package com.yadony.api.payments;

import com.yadony.api.payments.currency.CountryCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StripeConnectCountriesTest {

    @Test
    @DisplayName("Les pays de la zone euro sont couverts")
    void euroZoneIsSupported() {
        assertThat(StripeConnectCountries.isSupported("FR")).isTrue();
        assertThat(StripeConnectCountries.isSupported("BE")).isTrue();
        assertThat(StripeConnectCountries.isSupported("DE")).isTrue();
        assertThat(StripeConnectCountries.isSupported("ES")).isTrue();
        assertThat(StripeConnectCountries.isSupported("PT")).isTrue();
    }

    @Test
    @DisplayName("La Suisse et le Royaume-Uni sont couverts hors zone euro")
    void nonEuroEuropeIsSupported() {
        assertThat(StripeConnectCountries.isSupported("CH")).isTrue();
        assertThat(StripeConnectCountries.isSupported("GB")).isTrue();
    }

    @Test
    @DisplayName("Les zones XOF et XAF ne sont pas couvertes par Stripe")
    void africanCorridorsAreNotSupported() {
        for (String c : new String[] {
                "BJ", "BF", "CI", "GW", "ML", "NE", "SN", "TG",
                "CM", "CF", "TD", "CG", "GQ", "GA" }) {
            assertThat(StripeConnectCountries.isSupported(c))
                    .as("pays %s", c)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("Les Etats-Unis et le Canada ne sont pas couverts : Stripe y exige card_payments")
    void northAmericaIsNotSupported() {
        assertThat(StripeConnectCountries.isSupported("US")).isFalse();
        assertThat(StripeConnectCountries.isSupported("CA")).isFalse();
    }

    @Test
    @DisplayName("Le code pays est insensible a la casse")
    void isCaseInsensitive() {
        assertThat(StripeConnectCountries.isSupported("fr")).isTrue();
        assertThat(StripeConnectCountries.isSupported("Gb")).isTrue();
        assertThat(StripeConnectCountries.isSupported("sn")).isFalse();
    }

    @Test
    @DisplayName("Un pays absent ou inconnu n'est pas couvert")
    void nullOrUnknownIsNotSupported() {
        assertThat(StripeConnectCountries.isSupported(null)).isFalse();
        assertThat(StripeConnectCountries.isSupported("")).isFalse();
        assertThat(StripeConnectCountries.isSupported("ZZ")).isFalse();
    }

    @Test
    @DisplayName("Tout pays couvert par Stripe est un pays desservi par yadony")
    void supportedCountriesAreAllServedByYadony() {
        // L'inverse est faux par construction : yadony dessert 38 pays, Stripe n'en
        // couvre que 22. Mais annoncer Connect dans un pays que yadony ne dessert pas
        // n'aurait aucun sens — cette garde attrape une divergence des deux listes.
        for (String c : new String[] {
                "AT", "BE", "HR", "CY", "EE", "FI", "FR", "DE", "GR", "IE",
                "IT", "LV", "LT", "LU", "MT", "NL", "PT", "SK", "SI", "ES",
                "CH", "GB" }) {
            assertThat(CountryCatalog.isSupported(c))
                    .as("pays %s couvert par Stripe mais absent de CountryCatalog", c)
                    .isTrue();
        }
    }
}
