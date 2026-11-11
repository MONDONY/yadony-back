package com.yadony.api.dac7;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Taux de conversion vers l'euro pour la déclaration DAC7 : taux de RÉFÉRENCE du jour du versement (H7), exprimé en
 * unités de la devise pour un euro. Vide si le taux de ce jour n'est pas connu : le calcul ne devine jamais un taux,
 * il signale la ligne ({@code EXCHANGE_RATE_MISSING}).
 *
 * <p>En production : {@link EcbExchangeRateProvider} (parités fixes, puis taux de référence historiques de la BCE
 * copiés dans {@code ecb_exchange_rates}, lot 3b).
 */
public interface ExchangeRateProvider {

    Optional<BigDecimal> unitsPerEur(String currency, LocalDate day);
}
