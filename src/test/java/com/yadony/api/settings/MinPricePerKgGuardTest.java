package com.yadony.api.settings;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.currency.ExchangeRateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MinPricePerKgGuard — prix minimum par kilo du voyageur")
class MinPricePerKgGuardTest {

    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final BigDecimal RATE = new BigDecimal("0.12");

    @Mock private UserBusinessPrefsRepository repository;
    @Mock private ExchangeRateService exchangeRateService;
    @InjectMocks private MinPricePerKgGuard guard;

    private void prefs(int min, String currency) {
        UserBusinessPrefsEntity e = new UserBusinessPrefsEntity();
        e.setUserId(TRAVELER_ID);
        e.setMinBidPriceEur(min);
        e.setCurrencyCode(currency);
        when(repository.findById(TRAVELER_ID)).thenReturn(Optional.of(e));
    }

    private void sameCurrency() {
        when(exchangeRateService.convert(any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("net au kilo sous le minimum : 422 bid-below-min-price, minimum brut et devise")
    void belowMinimum_isRefused() {
        prefs(6, "EUR");
        sameCurrency();

        // 5 kg à 25 € net = 5 €/kg < 6 €/kg
        assertThatThrownBy(() -> guard.assertAtLeastMinimum(TRAVELER_ID, new BigDecimal("25.00"),
                new BigDecimal("5"), "eur", RATE))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo("bid-below-min-price");
                    // 6 × 1,12 = 6,72 € brut par kilo, ce que l'expéditeur doit proposer
                    assertThat(e.getProperties()).containsEntry("currency", "EUR");
                    assertThat((BigDecimal) e.getProperties().get("minPricePerKg"))
                            .isEqualByComparingTo("6.72");
                });
    }

    @Test
    @DisplayName("net au kilo égal ou supérieur au minimum : accepté")
    void atOrAboveMinimum_isAccepted() {
        prefs(6, "EUR");
        sameCurrency();

        assertThatCode(() -> guard.assertAtLeastMinimum(TRAVELER_ID, new BigDecimal("30.00"),
                new BigDecimal("5"), "EUR", RATE)).doesNotThrowAnyException();
        assertThatCode(() -> guard.assertAtLeastMinimum(TRAVELER_ID, new BigDecimal("40.00"),
                new BigDecimal("5"), "EUR", RATE)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("minimum à 0 : aucune restriction")
    void zeroMinimum_isIgnored() {
        prefs(0, "EUR");

        assertThatCode(() -> guard.assertAtLeastMinimum(TRAVELER_ID, new BigDecimal("1.00"),
                new BigDecimal("5"), "EUR", RATE)).doesNotThrowAnyException();
        verifyNoInteractions(exchangeRateService);
    }

    @Test
    @DisplayName("aucune préférence enregistrée : aucune restriction")
    void noPrefs_isIgnored() {
        when(repository.findById(TRAVELER_ID)).thenReturn(Optional.empty());

        assertThatCode(() -> guard.assertAtLeastMinimum(TRAVELER_ID, new BigDecimal("1.00"),
                new BigDecimal("5"), "EUR", RATE)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("offre sans poids (forfait, articles seuls) : pas d'équivalent au kilo, jamais bloquée")
    void noWeight_isNeverBlocked() {
        assertThatCode(() -> guard.assertAtLeastMinimum(TRAVELER_ID, new BigDecimal("1.00"),
                null, "EUR", RATE)).doesNotThrowAnyException();
        assertThatCode(() -> guard.assertAtLeastMinimum(TRAVELER_ID, new BigDecimal("1.00"),
                BigDecimal.ZERO, "EUR", RATE)).doesNotThrowAnyException();
        assertThatCode(() -> guard.assertAtLeastMinimum(TRAVELER_ID, null,
                new BigDecimal("5"), "EUR", RATE)).doesNotThrowAnyException();
        assertThatCode(() -> guard.assertAtLeastMinimum(null, new BigDecimal("1.00"),
                new BigDecimal("5"), "EUR", RATE)).doesNotThrowAnyException();
        verifyNoInteractions(repository, exchangeRateService);
    }

    @Test
    @DisplayName("devise du trajet différente : minimum converti dans la devise du trajet")
    void convertsMinimumIntoTripCurrency() {
        prefs(6, "EUR");
        // 6 € = 3 935,74 F CFA au taux administré
        when(exchangeRateService.convert(eq(BigDecimal.valueOf(6)), eq("EUR"), eq("XOF")))
                .thenReturn(new BigDecimal("3936"));

        // 5 kg à 15 000 F net = 3 000 F/kg < 3 936 F/kg
        assertThatThrownBy(() -> guard.assertAtLeastMinimum(TRAVELER_ID, new BigDecimal("15000"),
                new BigDecimal("5"), "XOF", null))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    // Sans taux de commission, le minimum est rendu tel quel, au franc près.
                    assertThat(e.getProperties()).isEqualTo(
                            Map.of("minPricePerKg", new BigDecimal("3936"), "currency", "XOF"));
                });
        // 5 kg à 20 000 F = 4 000 F/kg : accepté
        assertThatCode(() -> guard.assertAtLeastMinimum(TRAVELER_ID, new BigDecimal("20000"),
                new BigDecimal("5"), "XOF", null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("taux de change manquant : l'offre n'est pas bloquée")
    void missingRate_doesNotBlock() {
        prefs(6, "EUR");
        when(exchangeRateService.convert(any(), any(), any())).thenThrow(new YadonyBusinessException(
                HttpStatus.UNPROCESSABLE_ENTITY, ExchangeRateService.RATE_MISSING_CODE, "Missing", "x"));

        assertThatCode(() -> guard.assertAtLeastMinimum(TRAVELER_ID, new BigDecimal("1"),
                new BigDecimal("5"), "XOF", RATE)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("devise du trajet absente : EUR par défaut")
    void nullTripCurrency_defaultsToEur() {
        prefs(6, "EUR");
        sameCurrency();

        assertThatThrownBy(() -> guard.assertAtLeastMinimum(TRAVELER_ID, new BigDecimal("10"),
                new BigDecimal("5"), null, RATE))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getProperties()).containsEntry("currency", "EUR"));
    }
}
