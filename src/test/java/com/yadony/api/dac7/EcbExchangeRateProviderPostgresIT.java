package com.yadony.api.dac7;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Taux BCE lus dans la table {@code ecb_exchange_rates} (chaîne Flyway complète), sans aucun appel réseau : le client
 * est remplacé par une source en mémoire qui compte ses appels.
 */
@DisplayName("Taux BCE : cache par jour, jamais deviné, téléchargement à la demande")
class EcbExchangeRateProviderPostgresIT {

    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;
    private static NamedParameterJdbcTemplate named;

    private final FakeClient client = new FakeClient();

    @BeforeAll
    static void migrate() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        DataSource dataSource = postgres.getPostgresDatabase();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        named = new NamedParameterJdbcTemplate(dataSource);
    }

    @AfterAll
    static void stop() throws Exception {
        if (postgres != null) postgres.close();
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM ecb_exchange_rates");
    }

    private EcbExchangeRateProvider provider(boolean fetchEnabled, String now) {
        return new EcbExchangeRateProvider(new FixedParityExchangeRateProvider(), named, client,
                new Dac7EcbProperties(fetchEnabled, null), Clock.fixed(Instant.parse(now), ZoneOffset.UTC));
    }

    private static void rate(String currency, String day, String value) {
        jdbc.update("INSERT INTO ecb_exchange_rates (currency, rate_date, units_per_eur) VALUES (?, ?, ?)",
                currency, LocalDate.parse(day), new BigDecimal(value));
    }

    @Test
    @DisplayName("euro et franc CFA à parité fixe, sans lecture de la table")
    void pariteFixe() {
        EcbExchangeRateProvider p = provider(false, "2027-01-15T10:00:00Z");
        assertThat(p.unitsPerEur("EUR", LocalDate.parse("2026-03-07"))).contains(BigDecimal.ONE);
        assertThat(p.unitsPerEur("XOF", LocalDate.parse("2026-03-07"))).contains(new BigDecimal("655.957"));
        assertThat(p.unitsPerEur("XAF", LocalDate.parse("2026-03-07"))).contains(new BigDecimal("655.957"));
    }

    @Test
    @DisplayName("jour publié : son taux ; samedi : celui du vendredi ; aucune ligne après : inconnu ; devise absente : inconnu")
    void taux() {
        rate("USD", "2026-03-02", "1.0812");
        rate("USD", "2026-03-06", "1.0905");
        rate("USD", "2026-03-09", "1.1001");
        EcbExchangeRateProvider p = provider(false, "2027-01-15T10:00:00Z");
        assertThat(p.unitsPerEur("USD", LocalDate.parse("2026-03-02"))).get().isEqualTo(new BigDecimal("1.081200"));
        assertThat(p.unitsPerEur("USD", LocalDate.parse("2026-03-07"))).get()
                .usingComparator(BigDecimal::compareTo).isEqualTo(new BigDecimal("1.0905"));
        // Jour non publié : dernier taux publié AVANT lui (au plus 7 jours), avec sa vraie date (signalée).
        assertThat(p.rate("USD", LocalDate.parse("2026-03-10"))).get()
                .satisfies(r -> assertThat(r.rateDate()).isEqualTo(LocalDate.parse("2026-03-09")));
        assertThat(p.rate("USD", LocalDate.parse("2026-03-09"))).get()
                .satisfies(r -> assertThat(r.rateDate()).isEqualTo(LocalDate.parse("2026-03-09")));
        assertThat(p.rate("XOF", LocalDate.parse("2026-03-07"))).get()
                .satisfies(r -> assertThat(r.rateDate()).isEqualTo(LocalDate.parse("2026-03-07")));
        // Avant le premier taux connu.
        assertThat(p.unitsPerEur("USD", LocalDate.parse("2026-02-27"))).isEmpty();
        assertThat(p.unitsPerEur("CAD", LocalDate.parse("2026-03-02"))).isEmpty();
        assertThat(p.unitsPerEur(null, LocalDate.parse("2026-03-02"))).isEmpty();
        assertThat(p.unitsPerEur("USD", null)).isEmpty();
    }

    @Test
    @DisplayName("un trou de plus de 7 jours n'est jamais comblé par un vieux taux")
    void trouTropLong() {
        rate("USD", "2026-03-02", "1.08");
        rate("USD", "2026-03-20", "1.09");
        assertThat(provider(false, "2027-01-15T10:00:00Z").unitsPerEur("USD", LocalDate.parse("2026-03-12")))
                .isEmpty();
    }

    @Test
    @DisplayName("table incomplète pour l'année : télécharge, insère sans doublon, puis répond")
    void telechargeALaDemande() {
        rate("USD", "2026-01-02", "1.03");
        client.add("2026-01-02", "USD", "9.99");   // déjà en base : gardé tel quel
        client.add("2026-12-30", "USD", "1.12");
        client.add("2026-12-31", "USD", "1.13");
        client.add("2026-12-31", "GBP", "0.86");
        client.add("2025-12-31", "USD", "1.02");   // avant l'année : pas demandé
        EcbExchangeRateProvider p = provider(true, "2027-01-15T10:00:00Z");

        p.ensureCoverage(2026);

        assertThat(client.calls).containsExactly(LocalDate.parse("2026-01-01"));
        assertThat(jdbc.queryForObject("SELECT units_per_eur FROM ecb_exchange_rates WHERE currency = 'USD' "
                + "AND rate_date = DATE '2026-01-02'", BigDecimal.class)).isEqualByComparingTo("1.03");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ecb_exchange_rates", Integer.class)).isEqualTo(4);
        assertThat(p.unitsPerEur("GBP", LocalDate.parse("2026-12-31"))).isPresent();

        // Couverte : plus aucun appel.
        p.ensureCoverage(2026);
        assertThat(client.calls).hasSize(1);
    }

    @Test
    @DisplayName("fin d'année : table jusqu'au 28/12, les 29, 30 et 31/12 publiés sont téléchargés et gardent LEUR taux")
    void finDAnnee() {
        rate("USD", "2026-01-02", "1.03");
        rate("USD", "2026-12-28", "1.10");
        client.add("2026-12-29", "USD", "1.11");
        client.add("2026-12-30", "USD", "1.12");
        client.add("2026-12-31", "USD", "1.13");
        EcbExchangeRateProvider p = provider(true, "2027-01-10T10:00:00Z");

        p.ensureCoverage(2026);

        assertThat(client.calls).hasSize(1);
        for (String day : new String[]{"2026-12-29", "2026-12-30", "2026-12-31"}) {
            assertThat(p.rate("USD", LocalDate.parse(day))).get()
                    .satisfies(r -> assertThat(r.rateDate()).isEqualTo(LocalDate.parse(day)));
        }
        // Couverture attendue jusqu'au dernier jour ouvré : 31/12/2028 est un dimanche, vendredi 29/12 suffit.
        assertThat(EcbExchangeRateProvider.lastWeekday(LocalDate.parse("2028-12-31")))
                .isEqualTo(LocalDate.parse("2028-12-29"));
    }

    @Test
    @DisplayName("échec ou flux vide : un seul essai par heure, rien n'est écrit")
    void echecLimiteParHeure() {
        EcbExchangeRateProvider p = provider(true, "2027-01-15T10:00:00Z");
        p.ensureCoverage(2026);
        p.ensureCoverage(2026);
        assertThat(client.calls).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ecb_exchange_rates", Integer.class)).isZero();
    }

    @Test
    @DisplayName("téléchargement éteint ou année future : aucun appel")
    void aucunAppel() {
        provider(false, "2027-01-15T10:00:00Z").ensureCoverage(2026);
        provider(true, "2026-01-01T00:30:00Z").ensureCoverage(2027);
        assertThat(client.calls).isEmpty();
    }

    /** Source en mémoire, jamais le réseau. */
    static final class FakeClient extends EcbHistoricalRatesClient {
        final List<LocalDate> calls = new ArrayList<>();
        private final Map<LocalDate, Map<String, BigDecimal>> data = new java.util.TreeMap<>();

        FakeClient() {
            super(org.springframework.web.client.RestClient.create(), new Dac7EcbProperties(false, null));
        }

        void add(String day, String currency, String value) {
            data.computeIfAbsent(LocalDate.parse(day), d -> new java.util.HashMap<>())
                    .put(currency, new BigDecimal(value));
        }

        @Override
        public Map<LocalDate, Map<String, BigDecimal>> fetchSince(LocalDate from) {
            calls.add(from);
            Map<LocalDate, Map<String, BigDecimal>> out = new java.util.TreeMap<>();
            data.forEach((d, m) -> {
                if (!d.isBefore(from)) out.put(d, m);
            });
            return out;
        }
    }
}
