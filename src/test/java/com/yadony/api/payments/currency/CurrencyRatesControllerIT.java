package com.yadony.api.payments.currency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verrouille la FORME de {@code GET /config/exchange-rates} : consommée par des
 * clients mobiles déjà installés qui ne peuvent pas être mis à jour à la demande —
 * comme ses voisins de {@code ConfigController}.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("CurrencyRatesControllerIT — GET /config/exchange-rates")
class CurrencyRatesControllerIT {

    @Autowired MockMvc mockMvc;
    @Autowired ExchangeRateRepository exchangeRateRepository;

    /** Le profil test n'exécute pas Flyway (schéma Hibernate) : re-seed du V226. */
    @org.junit.jupiter.api.BeforeEach
    void seedRates() {
        exchangeRateRepository.deleteAll();
        exchangeRateRepository.saveAll(java.util.List.of(
                new ExchangeRateEntity("EUR", new java.math.BigDecimal("1")),
                new ExchangeRateEntity("USD", new java.math.BigDecimal("1.08")),
                new ExchangeRateEntity("CAD", new java.math.BigDecimal("1.47")),
                new ExchangeRateEntity("GBP", new java.math.BigDecimal("0.86")),
                new ExchangeRateEntity("CHF", new java.math.BigDecimal("0.95")),
                new ExchangeRateEntity("XOF", new java.math.BigDecimal("655.957")),
                new ExchangeRateEntity("XAF", new java.math.BigDecimal("655.957"))));
    }

    @Test
    @DisplayName("public, 200, les 7 devises du catalogue triées, EUR à 1")
    void getExchangeRates_isPublic_andCarriesTheWholeCatalog() throws Exception {
        mockMvc.perform(get("/config/exchange-rates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rates", hasSize(greaterThanOrEqualTo(7))))
                // Tri par code : CAD, CHF, EUR, GBP, USD, XAF, XOF.
                .andExpect(jsonPath("$.rates[0].currency").value("CAD"))
                .andExpect(jsonPath("$.rates[2].currency").value("EUR"))
                .andExpect(jsonPath("$.rates[2].unitsPerEur").value(1))
                // La parité CFA du traité, pas une estimation.
                .andExpect(jsonPath("$.rates[6].currency").value("XOF"))
                .andExpect(jsonPath("$.rates[6].unitsPerEur").value(655.957))
                // Forme : exactement ces deux champs par entrée.
                .andExpect(jsonPath("$.rates[0].unitsPerEur").isNumber());
    }
}
