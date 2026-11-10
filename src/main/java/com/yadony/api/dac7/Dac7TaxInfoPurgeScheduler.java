package com.yadony.api.dac7;

import com.yadony.api.common.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Purge mensuelle (RGPD, limitation de la conservation) des informations fiscales gardées sur les comptes supprimés :
 * NIF, pays d'émission, TVA, date de fourniture, pays de résidence et statut figés. Un compte n'est purgé que lorsque
 * plus aucune opération déclarable ne tombe dans la fenêtre {@code yadony.dac7.retention-years} (année courante et
 * précédente par défaut) : même règle que la conservation ({@link Dac7TaxInfoRetention#shouldRetain}).
 *
 * <p><b>Toujours actif</b>, même DAC7 éteint : c'est une mesure de protection. Cron {@code yadony.dac7.purge-cron}
 * (défaut le 1er du mois à 05:00 UTC). Idempotent (l'effacement n'écrit que s'il reste quelque chose, l'audit ne part
 * qu'alors). Un compte par transaction : une erreur est journalisée sans donnée et n'arrête pas les autres. Audit
 * {@code DAC7_TAX_INFO_PURGED} d'une ligne par compte purgé, sans valeur.
 */
@Component
public class Dac7TaxInfoPurgeScheduler {

    static final String AUDIT_ACTION = "DAC7_TAX_INFO_PURGED";

    private static final Logger log = LoggerFactory.getLogger(Dac7TaxInfoPurgeScheduler.class);

    private static final String REMAINS = """
            (tax_identification_number IS NOT NULL OR tax_country IS NOT NULL OR vat_number IS NOT NULL
             OR tax_info_provided_at IS NOT NULL OR tax_residence_country IS NOT NULL
             OR tax_seller_status IS NOT NULL)""";

    private static final String CANDIDATES_SQL = """
            SELECT id, country, tax_residence_country FROM users
            WHERE deleted_at IS NOT NULL AND """ + REMAINS + """

            ORDER BY id
            """;

    private static final String PURGE_SQL = """
            UPDATE users SET tax_identification_number = NULL, tax_country = NULL, vat_number = NULL,
                   tax_info_provided_at = NULL, tax_residence_country = NULL, tax_seller_status = NULL
            WHERE id = :id AND deleted_at IS NOT NULL AND """ + REMAINS;

    /** Bilan d'un passage. */
    public record PurgeOutcome(int purged, int kept, int failed) {
    }

    private record Candidate(UUID id, String country, String frozenResidenceCountry) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final Dac7TaxInfoRetention retention;
    private final AuditService audit;
    private final TransactionOperations perAccount;

    @Autowired
    public Dac7TaxInfoPurgeScheduler(NamedParameterJdbcTemplate jdbc, Dac7TaxInfoRetention retention,
                                     AuditService audit, PlatformTransactionManager transactionManager) {
        this(jdbc, retention, audit, requiresNew(transactionManager));
    }

    Dac7TaxInfoPurgeScheduler(NamedParameterJdbcTemplate jdbc, Dac7TaxInfoRetention retention, AuditService audit,
                              TransactionOperations perAccount) {
        this.jdbc = jdbc;
        this.retention = retention;
        this.audit = audit;
        this.perAccount = perAccount;
    }

    private static TransactionTemplate requiresNew(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    @Scheduled(cron = "${yadony.dac7.purge-cron:0 0 5 1 * *}", zone = "UTC")
    public void run() {
        PurgeOutcome outcome = purge();
        if (outcome.purged() > 0 || outcome.failed() > 0) {
            log.info("DAC7 : purge des comptes supprimés : {} purgé(s), {} gardé(s), {} en échec",
                    outcome.purged(), outcome.kept(), outcome.failed());
        }
    }

    public PurgeOutcome purge() {
        List<Candidate> candidates = jdbc.query(CANDIDATES_SQL, new MapSqlParameterSource(), (rs, i) -> new Candidate(
                rs.getObject("id", UUID.class), rs.getString("country"), rs.getString("tax_residence_country")));
        int purged = 0;
        int kept = 0;
        int failed = 0;
        for (Candidate candidate : candidates) {
            try {
                Boolean done = perAccount.execute(status -> purgeOne(candidate));
                if (Boolean.TRUE.equals(done)) {
                    purged++;
                } else {
                    kept++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("DAC7 : purge en échec pour le compte {} ({})", candidate.id(),
                        e.getClass().getSimpleName());
            }
        }
        return new PurgeOutcome(purged, kept, failed);
    }

    /** Vrai si le compte a été purgé par ce passage. */
    private boolean purgeOne(Candidate candidate) {
        String residence = Dac7ResidencyPolicy.residenceCountryOf(candidate.frozenResidenceCountry(),
                candidate.country(), null, null);
        if (retention.shouldRetain(residence, candidate.id())) {
            return false;
        }
        int updated = jdbc.update(PURGE_SQL, new MapSqlParameterSource("id", candidate.id()));
        if (updated == 0) {
            return false;
        }
        audit.log("USER", candidate.id(), AUDIT_ACTION, null, Map.of("retentionYears", retention.retentionYears()));
        return true;
    }
}
