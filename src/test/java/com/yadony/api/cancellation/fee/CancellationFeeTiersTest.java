package com.yadony.api.cancellation.fee;

import static org.assertj.core.api.Assertions.assertThat;

import com.yadony.api.matching.AnnouncementEntity;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class CancellationFeeTiersTest {

    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");
    private static final CancellationFeeProperties PROPS = new CancellationFeeProperties(false, null,
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

    /** Temps restant comparé exactement au seuil : ni arrondi à l'heure, ni à la minute. */
    @Test
    void rate_subHourBoundaries_areExact() {
        assertThat(rate(tripAt(NOW.plus(Duration.ofDays(5)).plus(Duration.ofMinutes(30))))).isEqualByComparingTo("0");
        assertThat(rate(tripAt(NOW.plus(Duration.ofDays(5)).plusSeconds(1)))).isEqualByComparingTo("0");
        assertThat(rate(tripAt(NOW.plus(Duration.ofDays(5)).minusSeconds(1)))).isEqualByComparingTo("0.10");
        assertThat(rate(tripAt(NOW.plus(Duration.ofDays(3)).minusSeconds(1)))).isEqualByComparingTo("0.20");
        assertThat(rate(tripAt(NOW.plus(Duration.ofDays(3)).plus(Duration.ofMinutes(30))))).isEqualByComparingTo("0.10");
        assertThat(rate(tripAt(NOW.plus(Duration.ofDays(1)).minusSeconds(1)))).isEqualByComparingTo("0.30");
        assertThat(rate(tripAt(NOW.plus(Duration.ofDays(1)).plus(Duration.ofMinutes(30))))).isEqualByComparingTo("0.20");
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

        // 26/11 00:00 UTC vu le 21/11 à 00:00 : 5 jours tout juste, 10 % ; une seconde plus tôt, plus de 5 jours.
        assertThat(CancellationFeeTiers.rateFor(trip(LocalDate.of(2026, 11, 26)), Instant.parse("2026-11-21T00:00:00Z"),
                PROPS)).isEqualByComparingTo("0.10");
        assertThat(CancellationFeeTiers.rateFor(trip(LocalDate.of(2026, 11, 26)), Instant.parse("2026-11-20T23:59:59Z"),
                PROPS)).isEqualByComparingTo("0");

        trip.setDepartureDate(LocalDate.of(2026, 11, 21)); // 00:00 UTC, déjà passé
        assertThat(rate(trip)).isEqualByComparingTo("0.30");
    }

    private static AnnouncementEntity trip(LocalDate departureDate) {
        AnnouncementEntity trip = new AnnouncementEntity();
        trip.setDepartureDate(departureDate);
        return trip;
    }

    @Test
    void rate_ignoresHandoverDeadline() {
        AnnouncementEntity trip = tripAt(NOW.plusSeconds(10L * 24 * 3600));
        trip.setHandoverDeadline(LocalDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC));
        assertThat(rate(trip)).isEqualByComparingTo("0");
    }

    // --- prochaine hausse du taux (aperçu montré à l'expéditeur) ---

    private static final Instant DEPARTURE = NOW.plus(Duration.ofDays(10));

    private static Instant nextAt(Instant now) {
        return CancellationFeeTiers.nextIncreaseAt(tripAt(DEPARTURE), now, PROPS);
    }

    private static BigDecimal rateAt(Instant now) {
        return CancellationFeeTiers.rateFor(tripAt(DEPARTURE), now, PROPS);
    }

    @Test
    void nextIncrease_freeWindow_isTheLastInstantAtZero() {
        // Plus de 5 jours : le palier gratuit exige strictement plus de 5 jours, il tient jusqu'à la dernière seconde
        // avant J-5 (inclus). À 5 jours tout juste, 10 %.
        Instant boundary = DEPARTURE.minus(Duration.ofDays(5)).minusSeconds(1);

        assertThat(nextAt(NOW)).isEqualTo(boundary);
        assertThat(nextAt(boundary)).isEqualTo(boundary);
        assertThat(rateAt(boundary)).isEqualByComparingTo("0");
        assertThat(rateAt(boundary.plusSeconds(1))).isEqualByComparingTo("0.10");
        // Plus d'une heure d'écart avec l'ancienne mesure en heures entières : 5 jours et 30 minutes restent gratuits.
        assertThat(rateAt(DEPARTURE.minus(Duration.ofDays(5)).minus(Duration.ofMinutes(30))))
                .isEqualByComparingTo("0");
    }

    /** L'aperçu montre le taux et sa prochaine hausse : le règlement à cet instant applique le même taux, pas après. */
    @Test
    void nextIncrease_isAlwaysTheLastInstantOfTheCurrentRate() {
        for (Instant now = DEPARTURE.minus(Duration.ofDays(7)); now.isBefore(DEPARTURE);
             now = now.plus(Duration.ofMinutes(17))) {
            Instant next = nextAt(now);
            if (next == null) {
                assertThat(rateAt(now)).isEqualByComparingTo("0.30");
                continue;
            }
            assertThat(rateAt(next)).as("taux à la hausse annoncée depuis %s", now).isEqualByComparingTo(rateAt(now));
            assertThat(rateAt(next.plusSeconds(1))).as("taux juste après depuis %s", now).isGreaterThan(rateAt(now));
        }
    }

    @Test
    void nextIncrease_tenPercentWindow_endsAtThreeDays() {
        Instant boundary = DEPARTURE.minus(Duration.ofHours(72));
        Instant justInTenPercent = DEPARTURE.minus(Duration.ofDays(5));

        assertThat(nextAt(justInTenPercent)).isEqualTo(boundary);
        assertThat(nextAt(boundary)).isEqualTo(boundary);
        assertThat(rateAt(boundary)).isEqualByComparingTo("0.10");
        assertThat(rateAt(boundary.plusSeconds(1))).isEqualByComparingTo("0.20");
    }

    @Test
    void nextIncrease_twentyPercentWindow_endsAtOneDay() {
        Instant boundary = DEPARTURE.minus(Duration.ofHours(24));

        assertThat(nextAt(DEPARTURE.minus(Duration.ofHours(72)).plusSeconds(1))).isEqualTo(boundary);
        assertThat(nextAt(DEPARTURE.minus(Duration.ofHours(48)))).isEqualTo(boundary);
        assertThat(nextAt(boundary)).isEqualTo(boundary);
        assertThat(rateAt(boundary)).isEqualByComparingTo("0.20");
        assertThat(rateAt(boundary.plusSeconds(1))).isEqualByComparingTo("0.30");
    }

    @Test
    void nextIncrease_maxTier_isNull() {
        assertThat(nextAt(DEPARTURE.minus(Duration.ofHours(24)).plusSeconds(1))).isNull();
        assertThat(nextAt(DEPARTURE.minusSeconds(60))).isNull();
        assertThat(nextAt(DEPARTURE.plus(Duration.ofDays(2)))).isNull();
    }

    @Test
    void nextIncrease_followsTheConfiguredScale_andSkipsEqualRates() {
        // Barème autre que celui de production : 7 j gratuits, puis 15 % jusqu'à 2 j, 15 % encore jusqu'à 1 j, 50 %.
        CancellationFeeProperties custom = new CancellationFeeProperties(false, null, new BigDecimal("30"), List.of(
                new CancellationFeeProperties.Tier(7, BigDecimal.ZERO),
                new CancellationFeeProperties.Tier(2, new BigDecimal("0.15")),
                new CancellationFeeProperties.Tier(1, new BigDecimal("0.15")),
                new CancellationFeeProperties.Tier(0, new BigDecimal("0.50"))));
        AnnouncementEntity trip = tripAt(DEPARTURE);

        assertThat(CancellationFeeTiers.nextIncreaseAt(trip, NOW, custom))
                .isEqualTo(DEPARTURE.minus(Duration.ofDays(7)).minusSeconds(1));
        // 15 % puis encore 15 % : la prochaine HAUSSE est le passage à 50 %, à J-1.
        assertThat(CancellationFeeTiers.nextIncreaseAt(trip, DEPARTURE.minus(Duration.ofHours(100)), custom))
                .isEqualTo(DEPARTURE.minus(Duration.ofHours(24)));
    }

    @Test
    void nextIncrease_noDepartureAt_usesDepartureDate() {
        AnnouncementEntity trip = new AnnouncementEntity();
        trip.setDepartureDate(LocalDate.of(2026, 11, 26)); // 26/11 00:00 UTC = 110 h après NOW : 10 %

        assertThat(CancellationFeeTiers.nextIncreaseAt(trip, NOW, PROPS))
                .isEqualTo(Instant.parse("2026-11-23T00:00:00Z"));
    }

    @Test
    void properties_bindFromApplicationYamlDefaults() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        sources.forEach(env.getPropertySources()::addLast);

        // Variables d'environnement résolues comme au démarrage (l'interrupteur et la date d'effet en dépendent).
        CancellationFeeProperties bound = new Binder(ConfigurationPropertySources.get(env),
                        new PropertySourcesPlaceholdersResolver(env))
                .bind("yadony.cancellation-fee", CancellationFeeProperties.class)
                .get();

        assertThat(bound.enabled()).isFalse();
        assertThat(bound.effectiveFrom()).isNull();
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
