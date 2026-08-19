package com.yadony.api.payments.currency;

import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExchangeRateServiceTest {

    @Mock
    private ExchangeRateRepository repository;

    private ExchangeRateService service;

    @BeforeEach
    void setUp() {
        service = new ExchangeRateService(repository);
    }

    @Test
    void rateOf_lit_le_taux_dans_la_table() {
        when(repository.findByCurrency("XOF"))
                .thenReturn(Optional.of(new ExchangeRateEntity("XOF", new BigDecimal("655.957000"))));

        BigDecimal rate = service.rateOf("XOF");

        assertThat(rate).isEqualByComparingTo("655.957000");
    }

    @Test
    void rateOf_devise_absente_leve_422_exchange_rate_missing() {
        when(repository.findByCurrency("XOF")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rateOf("XOF"))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(ex -> {
                    YadonyBusinessException businessException = (YadonyBusinessException) ex;
                    assertThat(businessException.getErrorCode()).isEqualTo("exchange-rate-missing");
                });
    }

    @Test
    void convert_eur_vers_xof_arrondit_a_zero_decimale() {
        when(repository.findByCurrency("XOF"))
                .thenReturn(Optional.of(new ExchangeRateEntity("XOF", new BigDecimal("655.957000"))));

        BigDecimal result = service.convert(new BigDecimal("10"), "EUR", "XOF");

        // 10 * 655.957 = 6559.57 -> arrondi HALF_UP a 0 decimale (XOF) = 6560
        assertThat(result).isEqualByComparingTo("6560");
    }

    @Test
    void convert_xof_vers_eur_arrondit_a_deux_decimales() {
        when(repository.findByCurrency("XOF"))
                .thenReturn(Optional.of(new ExchangeRateEntity("XOF", new BigDecimal("655.957000"))));

        BigDecimal result = service.convert(new BigDecimal("6560"), "XOF", "EUR");

        // 6560 / 655.957 = 10.00065... -> arrondi HALF_UP a 2 decimales (EUR) = 10.00
        assertThat(result).isEqualByComparingTo("10.00");
    }

    @Test
    void convert_meme_devise_rend_le_montant_inchange_sans_lire_la_base() {
        BigDecimal amount = new BigDecimal("42.50");

        BigDecimal result = service.convert(amount, "EUR", "EUR");

        assertThat(result).isEqualByComparingTo("42.50");
        verify(repository, never()).findByCurrency(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void convert_meme_devise_insensible_a_la_casse_ne_lit_pas_la_base() {
        BigDecimal amount = new BigDecimal("100");

        BigDecimal result = service.convert(amount, "xof", "XOF");

        assertThat(result).isEqualByComparingTo("100");
        verify(repository, never()).findByCurrency(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void convert_devise_cible_absente_leve_422_exchange_rate_missing() {
        when(repository.findByCurrency("XOF")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.convert(new BigDecimal("10"), "EUR", "XOF"))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(ex -> assertThat(((YadonyBusinessException) ex).getErrorCode())
                        .isEqualTo("exchange-rate-missing"));
    }

    @Test
    void convert_devise_source_absente_leve_422_exchange_rate_missing() {
        when(repository.findByCurrency("USD")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.convert(new BigDecimal("10"), "USD", "EUR"))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(ex -> assertThat(((YadonyBusinessException) ex).getErrorCode())
                        .isEqualTo("exchange-rate-missing"));
    }
}
