package com.yadony.api.dac7;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Taux de test : un taux par devise et par jour, ou un taux valable tous les jours. L'euro vaut toujours 1. Une devise
 * absente rend un taux inconnu, comme le ferait la vraie source un jour sans publication.
 */
class MapExchangeRateProvider implements ExchangeRateProvider {

    private final Map<String, BigDecimal> everyDay = new HashMap<>();
    private final Map<String, BigDecimal> byDay = new HashMap<>();

    MapExchangeRateProvider everyDay(String currency, String unitsPerEur) {
        everyDay.put(currency, new BigDecimal(unitsPerEur));
        return this;
    }

    MapExchangeRateProvider on(LocalDate day, String currency, String unitsPerEur) {
        byDay.put(currency + "@" + day, new BigDecimal(unitsPerEur));
        return this;
    }

    @Override
    public Optional<BigDecimal> unitsPerEur(String currency, LocalDate day) {
        if ("EUR".equals(currency)) {
            return Optional.of(BigDecimal.ONE);
        }
        BigDecimal dated = byDay.get(currency + "@" + day);
        return Optional.ofNullable(dated != null ? dated : everyDay.get(currency));
    }
}
