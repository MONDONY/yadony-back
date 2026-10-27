package com.yadony.api.payments.cash;

import com.yadony.api.payments.currency.ExchangeRateService;
import com.yadony.api.payments.currency.SupportedCurrency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * Conversion à arrondi choisi d'un {@link ExchangeRateService} simulé, aux taux fixes de la
 * production (1 € = 655,957 F CFA, 1,08 $, 0,86 £, 1,47 CAD, 0,95 CHF). La répartition d'une commission
 * demande ce que vaut chaque portefeuille dans la devise du colis (arrondi vers le bas) : les tests
 * qui simulent le service de taux ne stubbaient que les conversions précises de l'ancien calcul.
 */
public final class FixedRateConversions {

    private static final Map<String, BigDecimal> UNITS_PER_EUR = Map.of(
            "EUR", BigDecimal.ONE,
            "XOF", new BigDecimal("655.957"),
            "XAF", new BigDecimal("655.957"),
            "USD", new BigDecimal("1.08"),
            "GBP", new BigDecimal("0.86"),
            "CAD", new BigDecimal("1.47"),
            "CHF", new BigDecimal("0.95"));

    private FixedRateConversions() {
    }

    /** Branche {@code convert(montant, de, vers, arrondi)} sur les taux fixes (stub indulgent). */
    public static void stubRoundedConversion(ExchangeRateService exchangeRateService) {
        lenient().when(exchangeRateService.convert(any(BigDecimal.class), anyString(), anyString(),
                        any(RoundingMode.class)))
                .thenAnswer(invocation -> convert(invocation.getArgument(0), invocation.getArgument(1),
                        invocation.getArgument(2), invocation.getArgument(3)));
    }

    static BigDecimal convert(BigDecimal amount, String from, String to, RoundingMode mode) {
        if (from.equals(to)) {
            return amount;
        }
        BigDecimal inEur = amount.divide(UNITS_PER_EUR.get(from), 10, RoundingMode.HALF_UP);
        return inEur.multiply(UNITS_PER_EUR.get(to)).setScale(SupportedCurrency.fromCodeOrDefault(to).minorUnit(), mode);
    }
}
