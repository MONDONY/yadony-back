package com.yadony.api.flights;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static com.yadony.api.flights.FlightTestData.alert;
import static org.assertj.core.api.Assertions.assertThat;

class FlightAlertRuleTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 10, 0);

    @Test
    void firstPriceUnderThreshold_notifies() {
        FlightPriceAlertEntity a = alert(UUID.randomUUID(), "400");

        assertThat(FlightAlertRule.apply(a, new BigDecimal("350"), NOW)).isTrue();
        assertThat(a.getLastNotifiedPrice()).isEqualByComparingTo("350");
        assertThat(a.getLastNotifiedAt()).isEqualTo(NOW);
        assertThat(a.getLastSeenPrice()).isEqualByComparingTo("350");
        assertThat(a.getLastCheckedAt()).isEqualTo(NOW);
    }

    @Test
    void priceEqualToThreshold_notifies() {
        assertThat(FlightAlertRule.apply(alert(UUID.randomUUID(), "400"), new BigDecimal("400"), NOW)).isTrue();
    }

    @Test
    void samePriceAgain_doesNotNotify() {
        FlightPriceAlertEntity a = alert(UUID.randomUUID(), "400");
        FlightAlertRule.apply(a, new BigDecimal("350"), NOW);

        assertThat(FlightAlertRule.apply(a, new BigDecimal("350"), NOW.plusHours(6))).isFalse();
        assertThat(FlightAlertRule.apply(a, new BigDecimal("370"), NOW.plusHours(12))).isFalse();
        assertThat(a.getLastNotifiedPrice()).isEqualByComparingTo("350");
    }

    @Test
    void furtherDrop_notifiesAgain() {
        FlightPriceAlertEntity a = alert(UUID.randomUUID(), "400");
        FlightAlertRule.apply(a, new BigDecimal("350"), NOW);

        assertThat(FlightAlertRule.apply(a, new BigDecimal("320"), NOW.plusHours(6))).isTrue();
        assertThat(a.getLastNotifiedPrice()).isEqualByComparingTo("320");
    }

    @Test
    void priceBackAboveThreshold_resetsSoNextDropIsAnnounced() {
        FlightPriceAlertEntity a = alert(UUID.randomUUID(), "400");
        FlightAlertRule.apply(a, new BigDecimal("350"), NOW);

        assertThat(FlightAlertRule.apply(a, new BigDecimal("450"), NOW.plusHours(6))).isFalse();
        assertThat(a.getLastNotifiedPrice()).isNull();
        assertThat(FlightAlertRule.apply(a, new BigDecimal("380"), NOW.plusHours(12))).isTrue();
    }

    @Test
    void noPrice_onlyRecordsTheCheck() {
        FlightPriceAlertEntity a = alert(UUID.randomUUID(), "400");

        assertThat(FlightAlertRule.apply(a, null, NOW)).isFalse();
        assertThat(a.getLastCheckedAt()).isEqualTo(NOW);
        assertThat(a.getLastSeenPrice()).isNull();
    }
}
