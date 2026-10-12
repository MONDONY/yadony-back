package com.yadony.api.flights;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.yadony.api.flights.FlightTestData.price;
import static org.assertj.core.api.Assertions.assertThat;

class FlightDealCalculatorTest {

    @Test
    void cheapestWellBelowMedian_isADeal() {
        // Médiane de 300, 500, 520, 540, 600 = 520 ; 300 / 520 = 0,577 → −42 %.
        var deal = FlightDealCalculator.evaluate(List.of(price("540"), price("300"), price("600"),
                price("500"), price("520")));

        assertThat(deal).isPresent();
        assertThat(deal.get().cheapest().price()).isEqualByComparingTo("300");
        assertThat(deal.get().medianPrice()).isEqualByComparingTo("520.00");
        assertThat(deal.get().discountPercent()).isEqualTo(42);
    }

    @Test
    void discountUnderThreshold_isNotADeal() {
        // Médiane 500, moins cher 450 → −10 %.
        assertThat(FlightDealCalculator.evaluate(List.of(price("450"), price("500"), price("500"), price("520"))))
                .isEmpty();
    }

    @Test
    void exactlyTwentyPercent_isADeal() {
        // Médiane de 400, 500, 500, 500 = 500 → −20 %.
        assertThat(FlightDealCalculator.evaluate(List.of(price("400"), price("500"), price("500"), price("500"))))
                .hasValueSatisfying(d -> assertThat(d.discountPercent()).isEqualTo(20));
    }

    @Test
    void tooFewPrices_isNotADeal() {
        assertThat(FlightDealCalculator.evaluate(List.of(price("100"), price("500"), price("600")))).isEmpty();
    }

    @Test
    void invalidPricesAreIgnored() {
        TravelpayoutsPrice noPrice = new TravelpayoutsPrice("PAR", "ABJ", null, null, null, null, null,
                null, null, null, null, null, null, null);
        assertThat(FlightDealCalculator.evaluate(List.of(noPrice, price("100"), price("500"), price("600")))).isEmpty();
    }

    @Test
    void median_evenAndOddCounts() {
        assertThat(FlightDealCalculator.median(List.of(new BigDecimal("1"), new BigDecimal("3"), new BigDecimal("8"))))
                .isEqualByComparingTo("3");
        assertThat(FlightDealCalculator.median(List.of(new BigDecimal("1"), new BigDecimal("3"))))
                .isEqualByComparingTo("2");
    }
}
