package com.yadony.api.dac7;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Taux de conversion vers l'euro pour la déclaration DAC7 : taux de RÉFÉRENCE du jour du versement (H7), exprimé en
 * unités de la devise pour un euro. Vide si le taux de ce jour n'est pas connu : le calcul ne devine jamais un taux,
 * il signale la ligne ({@code EXCHANGE_RATE_MISSING}).
 *
 * <p>La source réelle des taux historiques de la BCE n'est pas encore branchée (la table {@code exchange_rates} ne
 * garde que le taux courant) : seule {@link FixedParityExchangeRateProvider} existe en production.
 */
public interface ExchangeRateProvider {

    Optional<BigDecimal> unitsPerEur(String currency, LocalDate day);
}
