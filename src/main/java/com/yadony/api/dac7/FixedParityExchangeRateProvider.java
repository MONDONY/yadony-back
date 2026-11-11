package com.yadony.api.dac7;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * Taux fixes, seuls connus sans source historique : l'euro (1) et le franc CFA (XOF et XAF, parité fixe de 655,957
 * par accord monétaire, que la BCE ne publie pas). Toute devise flottante (USD, CAD, GBP, CHF) rend un taux inconnu
 * ici : {@link EcbExchangeRateProvider}, qui passe d'abord par cette classe, la cherche ensuite dans les taux de
 * référence historiques de la BCE. Jamais un taux du jour appliqué à une date passée.
 */
@Component
public class FixedParityExchangeRateProvider implements ExchangeRateProvider {

    private static final Map<String, BigDecimal> FIXED = Map.of(
            "EUR", BigDecimal.ONE,
            "XOF", new BigDecimal("655.957"),
            "XAF", new BigDecimal("655.957"));

    @Override
    public Optional<BigDecimal> unitsPerEur(String currency, LocalDate day) {
        return Optional.ofNullable(currency == null ? null : FIXED.get(currency));
    }
}
