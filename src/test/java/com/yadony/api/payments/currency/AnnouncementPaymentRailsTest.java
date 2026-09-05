package com.yadony.api.payments.currency;

import static org.assertj.core.api.Assertions.assertThat;

import com.yadony.api.payments.cash.PaymentMethod;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AnnouncementPaymentRailsTest {

    /**
     * Table de vérité : 7 devises croisées avec Connect présent/absent (mobile money ajouté pour
     * les deux devises CFA où ce rail existe) = 14 cas exhaustifs. Revue finale, point 7 : chaque
     * devise à rail carte (EUR, USD, CAD, GBP, CHF) doit démontrer AU MOINS UNE FOIS, avec
     * Connect=true, que la carte est effectivement proposée — sans cette assertion pour une
     * devise donnée, la retirer du rail carte ne ferait rougir aucun test.
     */
    static Stream<Arguments> truthTable() {
        return Stream.of(
                Arguments.of("EUR", true, false, Set.of(PaymentMethod.STRIPE, PaymentMethod.CASH)),
                Arguments.of("EUR", false, false, Set.of(PaymentMethod.CASH)),
                // un compte mobile money ne change rien hors zone CFA
                Arguments.of("EUR", true, true, Set.of(PaymentMethod.STRIPE, PaymentMethod.CASH)),
                Arguments.of("USD", true, false, Set.of(PaymentMethod.STRIPE, PaymentMethod.CASH)),
                Arguments.of("CAD", true, false, Set.of(PaymentMethod.STRIPE, PaymentMethod.CASH)),
                Arguments.of("CAD", false, true, Set.of(PaymentMethod.CASH)),
                Arguments.of("GBP", true, false, Set.of(PaymentMethod.STRIPE, PaymentMethod.CASH)),
                Arguments.of("CHF", true, false, Set.of(PaymentMethod.STRIPE, PaymentMethod.CASH)),
                Arguments.of("CHF", false, false, Set.of(PaymentMethod.CASH)),
                // zone CFA : jamais de carte, mobile money seulement avec un compte actif
                Arguments.of("XOF", true, false, Set.of(PaymentMethod.CASH)),
                Arguments.of("XOF", false, true, Set.of(PaymentMethod.CASH, PaymentMethod.MOBILE_MONEY)),
                Arguments.of("XOF", true, true, Set.of(PaymentMethod.CASH, PaymentMethod.MOBILE_MONEY)),
                Arguments.of("XAF", false, false, Set.of(PaymentMethod.CASH)),
                Arguments.of("XAF", false, true, Set.of(PaymentMethod.CASH, PaymentMethod.MOBILE_MONEY))
        );
    }

    @ParameterizedTest(name = "{0} / connect={1} / mobileMoney={2} -> {3}")
    @MethodSource("truthTable")
    void availableFor_matchesTruthTable(String currency, boolean connect, boolean mobileMoney,
                                         Set<PaymentMethod> expected) {
        assertThat(AnnouncementPaymentRails.availableFor(currency, connect, mobileMoney)).isEqualTo(expected);
    }

    @Test
    void availableFor_neverEmpty_forAnyCombination() {
        for (SupportedCurrency currency : SupportedCurrency.values()) {
            for (boolean connect : new boolean[] {true, false}) {
                for (boolean mobileMoney : new boolean[] {true, false}) {
                    assertThat(AnnouncementPaymentRails.availableFor(currency.code(), connect, mobileMoney))
                            .as("currency=%s connect=%s mobileMoney=%s", currency.code(), connect, mobileMoney)
                            .isNotEmpty()
                            .contains(PaymentMethod.CASH);
                }
            }
        }
    }

    @Test
    void availableFor_unknownCurrency_fallsBackToEurRules() {
        assertThat(AnnouncementPaymentRails.availableFor("not-a-currency", true, true))
                .isEqualTo(Set.of(PaymentMethod.STRIPE, PaymentMethod.CASH));
        assertThat(AnnouncementPaymentRails.availableFor(null, false, true))
                .isEqualTo(Set.of(PaymentMethod.CASH));
    }
}
