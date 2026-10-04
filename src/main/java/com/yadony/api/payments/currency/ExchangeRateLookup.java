package com.yadony.api.payments.currency;

import com.yadony.api.common.YadonyBusinessException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Lecture en cache du taux d'une devise, séparée de {@link ExchangeRateService} : le
 * {@code @Cacheable} ne s'applique qu'aux appels qui traversent le proxy Spring, et
 * {@code convert}/{@code toEurPivot} appelaient {@code rateOf} depuis la même classe, ce qui
 * contournait le cache (un SELECT exchange_rates par conversion).
 *
 * <p>Cache {@code exchange-rates}, clé = code devise : évincé par
 * {@link ExchangeRateUpdateService} à chaque modification de taux.
 */
@Component
public class ExchangeRateLookup {

    private final ExchangeRateRepository repository;

    public ExchangeRateLookup(ExchangeRateRepository repository) {
        this.repository = repository;
    }

    @Cacheable(cacheNames = "exchange-rates", key = "#currency")
    public BigDecimal unitsPerEur(String currency) {
        return repository.findByCurrency(currency)
                .map(ExchangeRateEntity::getUnitsPerEur)
                .orElseThrow(() -> new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        ExchangeRateService.RATE_MISSING_CODE, "Exchange Rate Missing",
                        "Aucun taux de change n'est configure pour la devise " + currency,
                        Map.of("currency", currency)));
    }
}
