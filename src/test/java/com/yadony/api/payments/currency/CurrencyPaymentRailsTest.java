package com.yadony.api.payments.currency;

import static org.assertj.core.api.Assertions.assertThat;

import com.yadony.api.payments.cash.PaymentMethod;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * La table devise -> rails est une regle produit, pas une contrainte technique :
 * la zone CFA n'est pas un pays Stripe Connect, donc le versement au voyageur y est
 * impossible. La contrainte est <b>directionnelle</b> (l'encaissement, lui, marche),
 * ce qui explique que seule la voie « colis » soit fermee.
 */
class CurrencyPaymentRailsTest {

    @Test
    @DisplayName("L'euro accepte la carte et les especes")
    void eurAllowsStripeAndCash() {
        assertThat(CurrencyPaymentRails.allowedFor(SupportedCurrency.EUR))
                .containsExactlyInAnyOrder(PaymentMethod.STRIPE, PaymentMethod.CASH);
    }

    @ParameterizedTest
    @EnumSource(value = SupportedCurrency.class, names = {"XOF", "XAF"})
    @DisplayName("La zone CFA refuse Stripe et garde especes + mobile money")
    void cfaRefusesStripe(SupportedCurrency cfa) {
        assertThat(CurrencyPaymentRails.allowedFor(cfa))
                .containsExactlyInAnyOrder(
                        PaymentMethod.CASH, PaymentMethod.WAVE, PaymentMethod.ORANGE_MONEY)
                .doesNotContain(PaymentMethod.STRIPE);
    }

    @ParameterizedTest
    @EnumSource(value = SupportedCurrency.class, names = {"XOF", "XAF"})
    @DisplayName("allows() refuse explicitement la carte en zone CFA")
    void allowsRejectsStripeInCfa(SupportedCurrency cfa) {
        assertThat(CurrencyPaymentRails.allows(cfa, PaymentMethod.STRIPE)).isFalse();
        assertThat(CurrencyPaymentRails.allows(cfa, PaymentMethod.CASH)).isTrue();
    }

    @Test
    @DisplayName("allows() accepte la carte en euro")
    void allowsAcceptsStripeInEur() {
        assertThat(CurrencyPaymentRails.allows(SupportedCurrency.EUR, PaymentMethod.STRIPE)).isTrue();
    }

    @Test
    @DisplayName("Les especes restent le denominateur commun de toutes les devises")
    void cashIsAlwaysAllowed() {
        assertThat(SupportedCurrency.values())
                .allMatch(currency -> CurrencyPaymentRails.allows(currency, PaymentMethod.CASH));
    }

    @Test
    @DisplayName("Chaque devise du catalogue a une entree : aucune ne tombe dans un repli muet")
    void everyCurrencyIsMapped() {
        assertThat(SupportedCurrency.values())
                .allSatisfy(currency ->
                        assertThat(CurrencyPaymentRails.allowedFor(currency)).isNotEmpty());
    }

    @Test
    @DisplayName("Un code libre inconnu retombe sur le repli EUR unique")
    void unknownCodeFallsBackToEur() {
        assertThat(CurrencyPaymentRails.allowsCode("zzz", PaymentMethod.STRIPE)).isTrue();
        assertThat(CurrencyPaymentRails.allowsCode(null, PaymentMethod.STRIPE)).isTrue();
        assertThat(CurrencyPaymentRails.allowsCode("xof", PaymentMethod.STRIPE)).isFalse();
        assertThat(CurrencyPaymentRails.allowsCode("XOF", PaymentMethod.STRIPE)).isFalse();
    }

    @Test
    @DisplayName("La table est immuable : personne ne peut la modifier a chaud")
    void tableIsImmutable() {
        var rails = CurrencyPaymentRails.allowedFor(SupportedCurrency.EUR);
        assertThat(
                        Arrays.stream(PaymentMethod.values())
                                .filter(rails::contains)
                                .count())
                .isEqualTo(2);
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class, () -> rails.add(PaymentMethod.WAVE));
    }
}
