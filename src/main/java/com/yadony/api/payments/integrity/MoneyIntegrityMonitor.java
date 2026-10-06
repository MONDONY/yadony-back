package com.yadony.api.payments.integrity;

import com.yadony.api.admin.AdminAlertEscalator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sonde de cohérence de l'argent : exécute les {@link MoneyInvariants} toutes les 15 minutes,
 * chacune dans sa propre transaction en lecture seule (une requête en échec n'empêche pas les
 * autres), et :
 * <ul>
 *   <li>publie le nombre de lignes en faute par règle dans la jauge Prometheus
 *       {@code yadony_money_invariant_violations{invariant,severity}} ({@code -1} : requête en
 *       échec, aussi journalisée en ERROR) ;</li>
 *   <li>lève l'alerte {@code MONEY_INVARIANT_<code>} (persistée dans {@code admin_alerts},
 *       envoyée sur Telegram) quand le nombre augmente par rapport au passage précédent. Le
 *       premier passage après un démarrage part de zéro : une anomalie déjà connue est
 *       resignalée, sauf si son alerte n'a pas encore été résolue (déduplication de
 *       {@link AdminAlertEscalator#raiseOnce}). L'alerte porte la sévérité de la règle et un
 *       extrait des lignes en faute ({@code exemples}) ;</li>
 *   <li>ré-exécute une règle à la demande ({@link #inspect}) pour l'écran Alertes du
 *       back-office, qui montre ce qui reste en faute.</li>
 * </ul>
 */
@Component
public class MoneyIntegrityMonitor {

    private static final Logger log = LoggerFactory.getLogger(MoneyIntegrityMonitor.class);
    private static final String ALERT_PREFIX = "MONEY_INVARIANT_";
    private static final int QUERY_TIMEOUT_SECONDS = 30;
    /** Lignes en faute recopiées dans l'alerte : l'admin voit QUOI corriger, même si la ligne bouge ensuite. */
    static final int ALERT_SAMPLE_SIZE = 3;
    /** Plafond de lignes renvoyées à l'écran Alertes par {@link #inspect}. */
    public static final int MAX_INSPECT_ROWS = 100;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate readOnlyTransaction;
    private final AdminAlertEscalator alertEscalator;
    private final boolean enabled;
    private final Map<String, AtomicLong> levels = new ConcurrentHashMap<>();
    private final Map<String, Long> lastCounts = new ConcurrentHashMap<>();

    public MoneyIntegrityMonitor(JdbcTemplate jdbcTemplate,
                                 PlatformTransactionManager transactionManager,
                                 AdminAlertEscalator alertEscalator,
                                 MeterRegistry meterRegistry,
                                 @Value("${yadony.money-integrity.enabled:true}") boolean enabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.alertEscalator = alertEscalator;
        this.enabled = enabled;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
        this.readOnlyTransaction.setTimeout(QUERY_TIMEOUT_SECONDS);
        for (MoneyInvariant invariant : MoneyInvariants.ALL) {
            AtomicLong level = new AtomicLong();
            levels.put(invariant.code(), level);
            Gauge.builder("yadony.money.invariant.violations", level, AtomicLong::get)
                    .description("Lignes en faute par règle de cohérence de l'argent (-1 : requête en échec)")
                    .tag("invariant", invariant.code())
                    .tag("severity", invariant.severity().name())
                    .register(meterRegistry);
        }
    }

    @Scheduled(cron = "${yadony.money-integrity.cron:0 */15 * * * *}", zone = "UTC")
    public void scheduledRun() {
        if (enabled) {
            runAll();
        }
    }

    /**
     * Exécute toutes les règles et renvoie le nombre de lignes en faute par code. Une règle
     * dont la requête échoue est absente du résultat (jauge à -1, erreur journalisée).
     */
    public Map<String, Long> runAll() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (MoneyInvariant invariant : MoneyInvariants.ALL) {
            Long count;
            try {
                count = readOnlyTransaction.execute(status -> jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM (" + invariant.sql() + ") q", Long.class));
            } catch (RuntimeException e) {
                log.error("Règle de cohérence {} en échec : {}", invariant.code(), e.getMessage(), e);
                levels.get(invariant.code()).set(-1);
                continue;
            }
            long violations = count == null ? 0 : count;
            counts.put(invariant.code(), violations);
            levels.get(invariant.code()).set(violations);
            long previous = lastCounts.getOrDefault(invariant.code(), 0L);
            lastCounts.put(invariant.code(), violations);
            if (violations > previous) {
                raise(invariant, violations, previous);
            }
        }
        return counts;
    }

    private void raise(MoneyInvariant invariant, long violations, long previous) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("invariant", invariant.code());
        context.put("regle", invariant.title());
        context.put("gravite", invariant.severity().name());
        context.put("lignesEnFaute", violations);
        context.put("passagePrecedent", previous);
        List<Map<String, Object>> sample = sampleRows(invariant, ALERT_SAMPLE_SIZE);
        if (!sample.isEmpty()) {
            context.put("exemples", sample);
        }
        alertEscalator.raiseOnce(ALERT_PREFIX + invariant.code(), severityOf(invariant),
                "Incohérence d'argent " + invariant.code() + " (" + invariant.severity() + ") : "
                        + invariant.title() + " — " + violations + " ligne(s) en faute",
                context);
    }

    /** Sévérité back-office ({@code admin_alerts.severity}) d'une règle. */
    static String severityOf(MoneyInvariant invariant) {
        return invariant.severity() == MoneyInvariant.Severity.CRITIQUE ? "CRITICAL" : "WARN";
    }

    /**
     * Ré-exécute la règle {@code code} maintenant et renvoie ses lignes en faute (au plus
     * {@code limit}, plafonné à {@link #MAX_INSPECT_ROWS}), pour que l'écran Alertes montre ce
     * qui reste à corriger. Vide si le code ne désigne aucune règle.
     *
     * @throws org.springframework.dao.DataAccessException si la requête échoue
     */
    public Optional<Inspection> inspect(String code, int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_INSPECT_ROWS));
        return MoneyInvariants.ALL.stream()
                .filter(invariant -> invariant.code().equals(code))
                .findFirst()
                .map(invariant -> {
                    Long count = readOnlyTransaction.execute(status -> jdbcTemplate.queryForObject(
                            "SELECT count(*) FROM (" + invariant.sql() + ") q", Long.class));
                    return new Inspection(invariant.code(), invariant.title(), invariant.severity().name(),
                            count == null ? 0 : count, queryRows(invariant, bounded));
                });
    }

    /** Résultat de {@link #inspect} : la règle, son nombre total de lignes en faute, un extrait. */
    public record Inspection(String code, String title, String severity, long total,
                             List<Map<String, Object>> rows) {
    }

    private List<Map<String, Object>> sampleRows(MoneyInvariant invariant, int limit) {
        try {
            return queryRows(invariant, limit);
        } catch (RuntimeException e) {
            // L'alerte part quand même : l'extrait n'est qu'une aide.
            log.warn("Extrait de la règle {} indisponible : {}", invariant.code(), e.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> queryRows(MoneyInvariant invariant, int limit) {
        List<Map<String, Object>> rows = readOnlyTransaction.execute(status -> jdbcTemplate.queryForList(
                "SELECT * FROM (" + invariant.sql() + ") q LIMIT " + limit));
        if (rows == null) {
            return List.of();
        }
        return rows.stream().map(MoneyIntegrityMonitor::toJsonFriendly).toList();
    }

    /**
     * Les colonnes JDBC (Timestamp, PGInterval, UUID…) sont ramenées à des nombres, booléens et
     * textes : le résultat est sérialisé dans le payload JSONB et dans la réponse HTTP.
     */
    static Map<String, Object> toJsonFriendly(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((key, value) -> out.put(key, toJsonFriendly(value)));
        return out;
    }

    private static Object toJsonFriendly(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean || value instanceof String) {
            return value;
        }
        return String.valueOf(value);
    }
}
