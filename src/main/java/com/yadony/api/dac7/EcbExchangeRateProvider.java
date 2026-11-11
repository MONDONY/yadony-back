package com.yadony.api.dac7;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Taux de référence de la BCE du jour du versement (H7), source de {@link Dac7ReportService}. Ordre de lecture :
 * <ol>
 *   <li>parités fixes ({@link FixedParityExchangeRateProvider} : EUR, et XOF/XAF à 655,957, que la BCE ne publie
 *       pas) ;</li>
 *   <li>table {@code ecb_exchange_rates} (copie de {@code eurofxref-hist.xml}) : le taux du jour, ou, un jour sans
 *       publication (week-end, jour férié TARGET), celui du dernier jour publié dans les {@value #MAX_LOOKBACK_DAYS}
 *       jours qui précèdent, à condition qu'un taux PLUS RÉCENT existe déjà (preuve que le jour manquant n'a pas été
 *       publié, et non un téléchargement en retard).</li>
 * </ol>
 * Sinon : vide, jamais deviné ; le calcul signale la ligne ({@code EXCHANGE_RATE_MISSING}, {@code eurComplete=false}).
 *
 * <p>Aucun appel réseau pendant un calcul : {@link #ensureCoverage} télécharge, AVANT le calcul et hors transaction,
 * les taux d'une année si la table ne la couvre pas (page admin, export), au plus une fois par heure.
 */
@Primary
@Component
public class EcbExchangeRateProvider implements ExchangeRateProvider {

    private static final Logger log = LoggerFactory.getLogger(EcbExchangeRateProvider.class);

    static final int MAX_LOOKBACK_DAYS = 7;
    /** Marge de fin d'année : du 24/12 au 31/12, la BCE peut ne publier que deux ou trois jours. */
    private static final int END_TOLERANCE_DAYS = 4;
    private static final Duration RETRY_AFTER = Duration.ofHours(1);

    private static final String RATES_SQL =
            "SELECT rate_date, units_per_eur FROM ecb_exchange_rates WHERE currency = :currency";
    private static final String BOUNDS_SQL = "SELECT MIN(rate_date) AS first_day, MAX(rate_date) AS last_day "
            + "FROM ecb_exchange_rates WHERE rate_date >= :from AND rate_date <= :to";
    /** SQL portable (H2 et PostgreSQL) : une ligne déjà présente est gardée telle quelle. */
    private static final String INSERT_SQL = """
            INSERT INTO ecb_exchange_rates (currency, rate_date, units_per_eur, fetched_at)
            SELECT :currency, :day, :rate, :now
            WHERE NOT EXISTS (SELECT 1 FROM ecb_exchange_rates WHERE currency = :currency AND rate_date = :day)
            """;

    private final FixedParityExchangeRateProvider fixed;
    private final NamedParameterJdbcTemplate jdbc;
    private final EcbHistoricalRatesClient client;
    private final Dac7EcbProperties properties;
    private final Clock clock;
    private final Map<String, NavigableMap<LocalDate, BigDecimal>> cache = new ConcurrentHashMap<>();
    private final Map<Integer, Instant> lastAttempt = new ConcurrentHashMap<>();

    @Autowired
    public EcbExchangeRateProvider(FixedParityExchangeRateProvider fixed, NamedParameterJdbcTemplate jdbc,
                                   EcbHistoricalRatesClient client, Dac7EcbProperties properties) {
        this(fixed, jdbc, client, properties, Clock.systemUTC());
    }

    EcbExchangeRateProvider(FixedParityExchangeRateProvider fixed, NamedParameterJdbcTemplate jdbc,
                            EcbHistoricalRatesClient client, Dac7EcbProperties properties, Clock clock) {
        this.fixed = fixed;
        this.jdbc = jdbc;
        this.client = client;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Optional<BigDecimal> unitsPerEur(String currency, LocalDate day) {
        if (currency == null || day == null) {
            return Optional.empty();
        }
        Optional<BigDecimal> parity = fixed.unitsPerEur(currency, day);
        if (parity.isPresent()) {
            return parity;
        }
        NavigableMap<LocalDate, BigDecimal> rates = cache.computeIfAbsent(currency, this::load);
        Map.Entry<LocalDate, BigDecimal> floor = rates.floorEntry(day);
        if (floor == null || floor.getKey().isBefore(day.minusDays(MAX_LOOKBACK_DAYS))) {
            return Optional.empty();
        }
        if (!floor.getKey().equals(day) && rates.higherKey(day) == null) {
            return Optional.empty();
        }
        return Optional.of(floor.getValue());
    }

    /**
     * Télécharge les taux de {@code year} si la table ne couvre pas l'année jusqu'à hier (ou au 31/12 pour une année
     * close). Sans effet si le téléchargement est éteint, si l'année n'a pas commencé, ou si un essai a eu lieu il y a
     * moins d'une heure. Ne lève jamais : sans taux, les montants restent signalés incomplets.
     */
    public void ensureCoverage(int year) {
        if (!properties.fetchEnabledOrDefault()) {
            return;
        }
        Instant now = clock.instant();
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate first = LocalDate.of(year, 1, 1);
        LocalDate neededEnd = min(LocalDate.of(year, 12, 31), today.minusDays(1));
        if (neededEnd.isBefore(first) || covered(first, neededEnd)) {
            return;
        }
        Instant previous = lastAttempt.get(year);
        if (previous != null && previous.plus(RETRY_AFTER).isAfter(now)) {
            return;
        }
        lastAttempt.put(year, now);
        try {
            Map<LocalDate, Map<String, BigDecimal>> fetched = client.fetchSince(first);
            int inserted = 0;
            for (Map.Entry<LocalDate, Map<String, BigDecimal>> day : fetched.entrySet()) {
                if (day.getKey().getYear() != year) {
                    continue;
                }
                for (Map.Entry<String, BigDecimal> rate : day.getValue().entrySet()) {
                    inserted += insert(rate.getKey(), day.getKey(), rate.getValue(), now);
                }
            }
            cache.clear();
            log.info("Taux BCE {} : {} taux ajoutés", year, inserted);
        } catch (RuntimeException e) {
            log.warn("Taux BCE {} non mis à jour : {}", year, e.getClass().getSimpleName());
        }
    }

    private boolean covered(LocalDate first, LocalDate neededEnd) {
        Map<String, Object> bounds = jdbc.queryForMap(BOUNDS_SQL, new MapSqlParameterSource()
                .addValue("from", first).addValue("to", neededEnd));
        LocalDate firstDay = day(bounds.get("first_day"));
        LocalDate lastDay = day(bounds.get("last_day"));
        return firstDay != null && lastDay != null
                && !firstDay.isAfter(first.plusDays(MAX_LOOKBACK_DAYS))
                && !lastDay.isBefore(neededEnd.minusDays(END_TOLERANCE_DAYS));
    }

    private int insert(String currency, LocalDate day, BigDecimal rate, Instant now) {
        try {
            return jdbc.update(INSERT_SQL, new MapSqlParameterSource()
                    .addValue("currency", currency).addValue("day", day).addValue("rate", rate)
                    .addValue("now", java.time.OffsetDateTime.ofInstant(now, ZoneOffset.UTC)));
        } catch (DataIntegrityViolationException e) {
            // Insertion concurrente de la même ligne : elle est déjà là.
            return 0;
        }
    }

    private NavigableMap<LocalDate, BigDecimal> load(String currency) {
        NavigableMap<LocalDate, BigDecimal> rates = new TreeMap<>();
        jdbc.query(RATES_SQL, new MapSqlParameterSource("currency", currency),
                rs -> {
                    rates.put(rs.getObject("rate_date", LocalDate.class), rs.getBigDecimal("units_per_eur"));
                });
        return rates;
    }

    private static LocalDate day(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate d) {
            return d;
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        return LocalDate.parse(value.toString());
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
