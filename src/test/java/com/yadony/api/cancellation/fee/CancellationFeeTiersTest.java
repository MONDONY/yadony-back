package com.yadony.api.cancellation.fee;

import static org.assertj.core.api.Assertions.assertThat;

import com.yadony.api.matching.AnnouncementEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class CancellationFeeTiersTest {

    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");
    private static final CancellationFeeProperties PROPS = new CancellationFeeProperties(
            new BigDecimal("30"),
            List.of(
                    new CancellationFeeProperties.Tier(5, new BigDecimal("0")),
                    new CancellationFeeProperties.Tier(3, new BigDecimal("0.10")),
                    new CancellationFeeProperties.Tier(1, new BigDecimal("0.20")),
                    new CancellationFeeProperties.Tier(0, new BigDecimal("0.30"))));

    private static AnnouncementEntity tripAt(Instant departure) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setDepartureAt(departure.atOffset(ZoneOffset.UTC));
        return a;
    }

    private static BigDecimal rate(AnnouncementEntity trip) {
        return CancellationFeeTiers.rateFor(trip, NOW, PROPS);
    }

    @Test
    void rate_moreThan5Days_isZero() {
        assertThat(rate(tripAt(NOW.plusSeconds(120 * 3600 + 3600)))).isEqualByComparingTo("0");
        assertThat(rate(tripAt(NOW.plusSeconds(30L * 24 * 3600)))).isEqualByComparingTo("0");
    }

    @Test
    void rate_exactly5Days_is10Percent() {
        assertThat(rate(tripAt(NOW.plusSeconds(120 * 3600)))).isEqualByComparingTo("0.10");
    }

    @Test
    void rate_exactly3Days_is10Percent() {
        assertThat(rate(tripAt(NOW.plusSeconds(72 * 3600)))).isEqualByComparingTo("0.10");
    }

    @Test
    void rate_between3And1Days_is20Percent() {
        assertThat(rate(tripAt(NOW.plusSeconds(71 * 3600)))).isEqualByComparingTo("0.20");
        assertThat(rate(tripAt(NOW.plusSeconds(48 * 3600)))).isEqualByComparingTo("0.20");
    }

    @Test
    void rate_exactly1Day_is20Percent() {
        assertThat(rate(tripAt(NOW.plusSeconds(24 * 3600)))).isEqualByComparingTo("0.20");
    }

    @Test
    void rate_departureDay_is30Percent() {
        assertThat(rate(tripAt(NOW.plusSeconds(23 * 3600)))).isEqualByComparingTo("0.30");
        assertThat(rate(tripAt(NOW.plusSeconds(60)))).isEqualByComparingTo("0.30");
    }

    @Test
    void rate_afterDeparture_is30Percent() {
        assertThat(rate(tripAt(NOW.minusSeconds(3600)))).isEqualByComparingTo("0.30");
        assertThat(rate(tripAt(NOW.minusSeconds(10L * 24 * 3600)))).isEqualByComparingTo("0.30");
    }

    @Test
    void rate_noDepartureAt_usesDepartureDate() {
        AnnouncementEntity trip = new AnnouncementEntity();
        trip.setDepartureDate(LocalDate.of(2026, 11, 26)); // 26/11 00:00 UTC = 110 h après NOW
        assertThat(rate(trip)).isEqualByComparingTo("0.10");

        trip.setDepartureDate(LocalDate.of(2026, 11, 27)); // 134 h
        assertThat(rate(trip)).isEqualByComparingTo("0");

        trip.setDepartureDate(LocalDate.of(2026, 11, 21)); // 00:00 UTC, déjà passé
        assertThat(rate(trip)).isEqualByComparingTo("0.30");
    }

    @Test
    void rate_ignoresHandoverDeadline() {
        AnnouncementEntity trip = tripAt(NOW.plusSeconds(10L * 24 * 3600));
        trip.setHandoverDeadline(LocalDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC));
        assertThat(rate(trip)).isEqualByComparingTo("0");
    }

    @Test
    void properties_bindFromApplicationYamlDefaults() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        sources.forEach(env.getPropertySources()::addLast);

        CancellationFeeProperties bound = new Binder(ConfigurationPropertySources.get(env))
                .bind("yadony.cancellation-fee", CancellationFeeProperties.class)
                .get();

        assertThat(bound.capEur()).isEqualByComparingTo("30");
        assertThat(bound.tiers()).extracting(CancellationFeeProperties.Tier::minDaysBefore)
                .containsExactly(5, 3, 1, 0);
        assertThat(bound.tiers()).extracting(CancellationFeeProperties.Tier::rate)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("0"), new BigDecimal("0.10"),
                        new BigDecimal("0.20"), new BigDecimal("0.30"));
        assertThat(CancellationFeeTiers.rateFor(tripAt(NOW.plusSeconds(72 * 3600)), NOW, bound))
                .isEqualByComparingTo("0.10");
    }
}
