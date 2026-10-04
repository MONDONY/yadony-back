package com.yadony.api.payments.currency;

import com.yadony.api.config.CacheConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code convert} et {@code toEurPivot} sont appelés pour chaque annonce d'une page de
 * recherche (prix converti, pivot EUR) : le taux doit venir du cache {@code exchange-rates}
 * et non d'un SELECT exchange_rates par conversion.
 */
@SpringJUnitConfig(classes = {CacheConfig.class, ExchangeRateService.class, ExchangeRateLookup.class})
@DisplayName("ExchangeRateService — taux lus en cache")
class ExchangeRateServiceCacheTest {

    @Autowired ExchangeRateService service;
    @Autowired CacheManager cacheManager;
    @MockitoBean ExchangeRateRepository repository;

    @BeforeEach
    void setUp() {
        cacheManager.getCache("exchange-rates").clear();
        reset(repository);
        when(repository.findByCurrency("XOF"))
                .thenReturn(Optional.of(new ExchangeRateEntity("XOF", new BigDecimal("655.957000"))));
    }

    @Test
    void convertEtToEurPivot_neLisentLeTauxQuUneFois() {
        BigDecimal first = service.convert(new BigDecimal("10"), "EUR", "XOF");
        BigDecimal second = service.convert(new BigDecimal("20"), "EUR", "XOF");
        BigDecimal pivot = service.toEurPivot(new BigDecimal("6559.57"), "XOF");

        assertThat(first).isEqualByComparingTo("6560");
        assertThat(second).isEqualByComparingTo("13119");
        assertThat(pivot).isEqualByComparingTo("10.0000");
        verify(repository, times(1)).findByCurrency("XOF");
    }

    @Test
    void evictionDuCache_faitRelireLeNouveauTaux() {
        service.convert(new BigDecimal("10"), "EUR", "XOF");
        when(repository.findByCurrency("XOF"))
                .thenReturn(Optional.of(new ExchangeRateEntity("XOF", new BigDecimal("700"))));

        cacheManager.getCache("exchange-rates").evict("XOF");

        assertThat(service.convert(new BigDecimal("10"), "EUR", "XOF")).isEqualByComparingTo("7000");
    }
}
