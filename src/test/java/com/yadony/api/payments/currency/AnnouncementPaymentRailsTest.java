package com.yadony.api.payments.currency;

import static org.assertj.core.api.Assertions.assertThat;

import com.yadony.api.payments.cash.PaymentMethod;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

class AnnouncementPaymentRailsTest {

    /** Table de vérité : 7 devises croisées avec Connect présent/absent = 14 cas. */
    static Stream<Arguments> truthTable() {
        return Stream.of(
                // devises rails carte (EUR, USD, CAD, GBP, CHF) : carte dispo seulement avec Connect
                Arguments.of("EUR", true, Set.of(PaymentMethod.STRIPE, PaymentMethod.CASH)),
                Arguments.of("EUR", false, Set.of(PaymentMethod.CASH)),
                Arguments.of("USD", true, Set.of(PaymentMethod.STRIPE, PaymentMethod.CASH)),
                Arguments.of("USD", false, Set.of(PaymentMethod.CASH)),
                Arguments.of("CAD", true, Set.of(PaymentMethod.STRIPE, PaymentMethod.CASH)),
                Arguments.of("CAD", false, Set.of(PaymentMethod.CASH)),
                Arguments.of("GBP", true, Set.of(PaymentMethod.STRIPE, PaymentMethod.CASH)),
                Arguments.of("GBP", false, Set.of(PaymentMethod.CASH)),
                Arguments.of("CHF", true, Set.of(PaymentMethod.STRIPE, PaymentMethod.CASH)),
                Arguments.of("CHF", false, Set.of(PaymentMethod.CASH)),
                // zone CFA (XOF, XAF) : jamais de carte, meme avec Connect actif
                Arguments.of("XOF", true, Set.of(PaymentMethod.CASH)),
                Arguments.of("XOF", false, Set.of(PaymentMethod.CASH)),
                Arguments.of("XAF", true, Set.of(PaymentMethod.CASH)),
                Arguments.of("XAF", false, Set.of(PaymentMethod.CASH))
        );
    }

    @ParameterizedTest(name = "{0} / travelerHasConnect={1} -> {2}")
    @MethodSource("truthTable")
    void availableFor_matchesTruthTable(String currency, boolean travelerHasConnect,
                                         Set<PaymentMethod> expected) {
        assertThat(AnnouncementPaymentRails.availableFor(currency, travelerHasConnect))
                .isEqualTo(expected);
    }

    /**
     * Invariant central : aucune combinaison devise x Connect ne doit jamais rendre un
     * ensemble vide. Ce test doit échouer si quelqu'un retire l'espèce un jour.
     */
    @Test
    void availableFor_neverEmpty_forAnyCurrencyAndConnectState() {
        for (SupportedCurrency currency : SupportedCurrency.values()) {
            for (boolean travelerHasConnect : new boolean[] {true, false}) {
                Set<PaymentMethod> available =
                        AnnouncementPaymentRails.availableFor(currency.code(), travelerHasConnect);
                assertThat(available)
                        .as("currency=%s travelerHasConnect=%s", currency.code(), travelerHasConnect)
                        .isNotEmpty()
                        .contains(PaymentMethod.CASH);
            }
        }
    }

    @Test
    void availableFor_unknownCurrency_fallsBackToEurRules() {
        assertThat(AnnouncementPaymentRails.availableFor("not-a-currency", true))
                .isEqualTo(Set.of(PaymentMethod.STRIPE, PaymentMethod.CASH));
        assertThat(AnnouncementPaymentRails.availableFor(null, false))
                .isEqualTo(Set.of(PaymentMethod.CASH));
    }
}
