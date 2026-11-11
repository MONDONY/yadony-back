package com.yadony.api.dac7;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Lectures SQL du lot 3b autour du registre des diligences, des récapitulatifs et des rappels. Le registre et les
 * comptes admin vivent dans d'autres tables que celles du paquet : lecture seule, sauf la réservation d'un
 * récapitulatif. Aucune donnée fiscale lue ici (le NIF n'est testé qu'en {@code IS NULL}). SQL portable (H2 et
 * PostgreSQL).
 */
@Component
public class Dac7AdminReadModel {

    static final List<String> OUTGOING = List.of("REQUEST", "REMINDER_1", "REMINDER_2");

    /** Une ligne du registre, avec l'adresse de l'administrateur auteur s'il y en a un. */
    public record DiligenceRow(Instant at, String action, String channel, String actorName, String note) {
    }

    /** Voyageur peut-être à solliciter : pays et statut pour la règle de résidence (aucune autre donnée). */
    public record ReminderCandidate(UUID userId, String country, String travelerStatus, String businessCountry,
                                    String frozenResidenceCountry) {
    }

    private static final String LAST_DILIGENCE_SQL = "SELECT user_id, MAX(created_at) AS last_at "
            + "FROM dac7_due_diligence_log WHERE user_id IN (:ids) GROUP BY user_id";
    private static final String DILIGENCE_SQL = """
            SELECT l.created_at, l.action, l.channel, l.note, a.email AS actor_email
            FROM dac7_due_diligence_log l
            LEFT JOIN admin_users a ON a.id = l.actor_id
            WHERE l.user_id = :id
            ORDER BY l.created_at, l.id
            """;
    private static final String LAST_OUTGOING_SQL = "SELECT MAX(created_at) FROM dac7_due_diligence_log "
            + "WHERE user_id = :id AND action IN (:actions)";
    private static final String CANDIDATES_SQL = """
            SELECT u.id, COALESCE(u.country, u.tax_declared_residence_country) AS country, u.traveler_status,
                   u.business_country, u.tax_residence_country
            FROM users u
            WHERE u.deleted_at IS NULL AND u.tax_identification_number IS NULL AND u.tax_reminder_count < 2
              AND (u.country IS NOT NULL OR u.business_country IS NOT NULL
                   OR u.tax_declared_residence_country IS NOT NULL)
              AND (u.tax_info_requested_at IS NULL
                   OR (u.tax_reminder_count = 0 AND u.tax_info_requested_at <= :firstDue)
                   OR (u.tax_reminder_count = 1 AND u.tax_info_requested_at <= :secondDue))
            ORDER BY u.id
            """;
    private static final String RESERVE_STATEMENT_SQL = """
            INSERT INTO dac7_seller_statements (user_id, reporting_year, sent_by, sent_at)
            SELECT :userId, :year, :adminId, :now
            WHERE NOT EXISTS (SELECT 1 FROM dac7_seller_statements WHERE user_id = :userId AND reporting_year = :year)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public Dac7AdminReadModel(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<UUID, Instant> lastDiligence(Collection<UUID> ids) {
        Map<UUID, Instant> result = new HashMap<>();
        List<UUID> all = new ArrayList<>(ids);
        for (int start = 0; start < all.size(); start += Dac7ReportReadModel.IN_CHUNK) {
            List<UUID> chunk = all.subList(start, Math.min(all.size(), start + Dac7ReportReadModel.IN_CHUNK));
            jdbc.query(LAST_DILIGENCE_SQL, new MapSqlParameterSource("ids", chunk),
                    rs -> {
                        result.put(uuid(rs, "user_id"), instant(rs, "last_at"));
                    });
        }
        return result;
    }

    public List<DiligenceRow> diligence(UUID userId) {
        return jdbc.query(DILIGENCE_SQL, new MapSqlParameterSource("id", userId), (rs, i) -> new DiligenceRow(
                instant(rs, "created_at"), rs.getString("action"), rs.getString("channel"),
                rs.getString("actor_email"), rs.getString("note")));
    }

    /** Dernier message envoyé au voyageur (demande ou rappel, tout canal). */
    public Optional<Instant> lastOutgoing(UUID userId) {
        List<Instant> rows = jdbc.query(LAST_OUTGOING_SQL, new MapSqlParameterSource()
                .addValue("id", userId).addValue("actions", OUTGOING), (rs, i) -> instant(rs, 1));
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.get(0));
    }

    public List<ReminderCandidate> reminderCandidates(Instant firstDue, Instant secondDue) {
        return jdbc.query(CANDIDATES_SQL, new MapSqlParameterSource()
                        .addValue("firstDue", OffsetDateTime.ofInstant(firstDue, ZoneOffset.UTC))
                        .addValue("secondDue", OffsetDateTime.ofInstant(secondDue, ZoneOffset.UTC)),
                (rs, i) -> new ReminderCandidate(uuid(rs, "id"), rs.getString("country"),
                        rs.getString("traveler_status"), rs.getString("business_country"),
                        rs.getString("tax_residence_country")));
    }

    /** Une ligne à inscrire pour un voyageur d'un export : retenu (référence) ou écarté (raison). */
    public record ExportSellerRow(UUID userId, boolean included, String docRefId, String reason) {
    }

    /** Retenus dans un run DÉPOSÉ de l'année : un fichier généré mais jamais déposé n'écarte personne. */
    private static final String EXPORTED_SQL = """
            SELECT DISTINCT s.user_id FROM dac7_export_sellers s
            JOIN dac7_export_runs r ON r.id = s.export_run_id
            WHERE s.reporting_year = :year AND s.status = 'INCLUDED' AND r.status = 'DEPOSITED'
            """;
    private static final String INSERT_RUN_SQL = """
            INSERT INTO dac7_export_runs (id, reporting_year, sequence, export_mode, status, file_name, sellers,
              generated_by, generated_at)
            VALUES (:id, :year, :sequence, :mode, 'GENERATED', :fileName, :sellers, :adminId, :now)
            """;
    private static final String MARK_DEPOSITED_SQL = """
            UPDATE dac7_export_runs SET status = 'DEPOSITED', deposited_by = :adminId, deposited_at = :now
            WHERE id = :id AND status = 'GENERATED'
            """;

    /** Un fichier généré : année, ordre, mode, nombre de voyageurs retenus, statut, date de génération. */
    public record ExportRun(UUID id, int year, int sequence, String mode, String status, int sellers,
                            Instant generatedAt) {
    }

    /** Ordre DGFiP suivant pour l'année (0 pour le premier fichier), jamais réemployé. */
    public int nextSequence(int year) {
        Integer max = jdbc.queryForObject("SELECT MAX(sequence) FROM dac7_export_runs WHERE reporting_year = :year",
                new MapSqlParameterSource("year", year), Integer.class);
        return max == null ? 0 : max + 1;
    }

    public boolean sequenceUsed(int year, int sequence) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM dac7_export_runs WHERE reporting_year = :year "
                + "AND sequence = :sequence", new MapSqlParameterSource().addValue("year", year)
                .addValue("sequence", sequence), Integer.class);
        return count != null && count > 0;
    }

    /**
     * Verrou de transaction par année, SANS attente (PostgreSQL : {@code pg_try_advisory_xact_lock}, une clé par
     * année, libérée à la fin de la transaction, succès ou échec) : faux s'il est déjà pris par un autre export ou
     * dépôt de la même année. À appeler dans une transaction (sinon le verrou serait relâché aussitôt). Toujours vrai
     * sur une autre base (H2 des tests de contexte).
     */
    public boolean tryLockYear(int year) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Le verrou d'export DAC7 se prend dans une transaction.");
        }
        Boolean postgres = jdbc.getJdbcTemplate().execute((java.sql.Connection c) ->
                c.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("postgres"));
        if (!Boolean.TRUE.equals(postgres)) {
            return true;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(:key)",
                new MapSqlParameterSource("key", LOCK_KEY_BASE + year), Boolean.class));
    }

    /** Clés {@code 7 000 000 000 + année} : sans collision avec le verrou de la grille de commission. */
    static final long LOCK_KEY_BASE = 7_000_000_000L;

    public void insertRun(UUID id, int year, int sequence, String mode, String fileName, int sellers, UUID adminId,
                          Instant now) {
        jdbc.update(INSERT_RUN_SQL, new MapSqlParameterSource().addValue("id", id).addValue("year", year)
                .addValue("sequence", sequence).addValue("mode", mode).addValue("fileName", fileName)
                .addValue("sellers", sellers).addValue("adminId", adminId)
                .addValue("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)));
    }

    public Optional<ExportRun> run(UUID id) {
        List<ExportRun> runs = jdbc.query("SELECT id, reporting_year, sequence, export_mode, status, sellers, "
                + "generated_at FROM dac7_export_runs WHERE id = :id", new MapSqlParameterSource("id", id), (rs, i) ->
                new ExportRun(uuid(rs, "id"), rs.getInt("reporting_year"), rs.getInt("sequence"),
                        rs.getString("export_mode"), rs.getString("status"), rs.getInt("sellers"),
                        instant(rs, "generated_at")));
        return runs.stream().findFirst();
    }

    /** Vrai si le run vient de passer DEPOSITED, faux s'il l'était déjà (idempotent). */
    public boolean markDeposited(UUID id, UUID adminId, Instant now) {
        return jdbc.update(MARK_DEPOSITED_SQL, new MapSqlParameterSource().addValue("id", id)
                .addValue("adminId", adminId).addValue("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))) == 1;
    }
    private static final String RECORD_EXPORT_SQL = """
            INSERT INTO dac7_export_sellers (reporting_year, user_id, export_run_id, status, doc_ref_id, reason,
              sequence, export_mode, exported_by, exported_at)
            VALUES (:year, :userId, :runId, :status, :docRefId, :reason, :sequence, :mode, :adminId, :now)
            """;

    /** Voyageurs retenus dans un fichier DÉPOSÉ de cette année (à relire sous le verrou avant une inscription). */
    public java.util.Set<UUID> exportedTravelers(int year) {
        return new java.util.HashSet<>(jdbc.query(EXPORTED_SQL, new MapSqlParameterSource("year", year),
                (rs, i) -> uuid(rs, "user_id")));
    }

    /** Inscrit chaque voyageur d'une génération, dans la transaction de l'appelant. */
    public void recordExport(int year, UUID runId, int sequence, String mode, UUID adminId, Instant now,
                             List<ExportSellerRow> rows) {
        for (ExportSellerRow row : rows) {
            jdbc.update(RECORD_EXPORT_SQL, new MapSqlParameterSource()
                    .addValue("year", year).addValue("userId", row.userId()).addValue("runId", runId)
                    .addValue("status", row.included() ? "INCLUDED" : "EXCLUDED")
                    .addValue("docRefId", row.docRefId()).addValue("reason", row.reason())
                    .addValue("sequence", sequence).addValue("mode", mode).addValue("adminId", adminId)
                    .addValue("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)));
        }
    }

    /** Le compte existe-t-il, supprimé compris (le registre survit à la suppression) ? */
    public boolean userExists(UUID userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = :id",
                new MapSqlParameterSource("id", userId), Integer.class);
        return count != null && count > 0;
    }

    /** Réserve le récapitulatif ; faux s'il a déjà été envoyé pour cette année. */
    public boolean reserveStatement(UUID userId, int year, UUID adminId, Instant now) {
        return jdbc.update(RESERVE_STATEMENT_SQL, new MapSqlParameterSource()
                .addValue("userId", userId).addValue("year", year).addValue("adminId", adminId)
                .addValue("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))) == 1;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        return value instanceof UUID u ? u : UUID.fromString(value.toString());
    }
}
