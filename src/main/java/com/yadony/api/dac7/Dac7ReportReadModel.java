package com.yadony.api.dac7;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Lecture SQL, seule, des montants versés aux voyageurs pour DAC7. Même choix que
 * {@code payments.overview.MoneyOverviewReadModel} : les données vivent dans plusieurs paquets (paiements,
 * colis, suppléments, modifications, fils de négociation, bons de parrainage) et CLAUDE.md interdit d'injecter leurs
 * services ici ; un événement ne convient pas à une lecture synchrone. Aucune écriture, aucune règle métier recopiée :
 * les prédicats reprennent ceux des écrans d'argent, du versement et de la sonde d'intégrité.
 *
 * <p>NE PAS remplacer par {@code FiscalExportService} : il additionne {@code amount} sans regarder la devise et ignore
 * les espèces.
 *
 * <p>Dates : les colonnes sans fuseau ({@code bids.delivered_at}, {@code paid_out_at}, {@code refunded_at},
 * {@code payment_splits.completed_at}) sont écrites en UTC par l'application et lues {@code AT TIME ZONE 'UTC'}, comme
 * le fait {@code MoneyInvariants}. Les colis et trajets supprimés ne sont PAS écartés : l'argent versé reste un fait
 * fiscal ; seuls les paiements, suppléments et modifications supprimés le sont, comme le font leurs entités.
 */
@Component
public class Dac7ReportReadModel {

    /** Paquets de la clause {@code IN} : un paramètre JDBC par identifiant, borné. */
    static final int IN_CHUNK = 500;

    /**
     * Une ligne versée au voyageur.
     *
     * @param net               contrepartie nette de la commission retenue, dans la devise d'origine
     * @param commission        commission retenue par Yadony ; {@code null} si elle est introuvable
     * @param paidAt            versement (carte, mobile money), fin du partage admin, ou livraison (espèces)
     * @param countsAsOperation vrai pour un colis payé (paiement ou espèces), faux pour un supplément ou une modification
     * @param dateEstimated     vrai si la date de livraison manque et que la dernière mise à jour du colis la remplace
     */
    public record OperationRow(Dac7Source source, UUID sourceId, UUID travelerId, String rail, String currency,
                               BigDecimal net, BigDecimal commission, Instant paidAt, boolean countsAsOperation,
                               boolean dateEstimated) {
    }

    /**
     * Un remboursement à l'expéditeur, une ligne par remboursement (les remboursements partiels successifs sont
     * séparés, chacun à sa date).
     *
     * @param amount              montant de CE remboursement, dans la devise d'origine
     * @param operationNet        net de l'opération remboursée (plafond d'une déduction)
     * @param refundDateEstimated vrai si la date vient de la dernière mise à jour du paiement, faute d'audit
     * @param afterRelease        vrai si le remboursement a eu lieu après le versement au voyageur
     */
    public record RefundRow(Dac7Source source, UUID sourceId, UUID travelerId, String currency, BigDecimal amount,
                            BigDecimal operationNet, Instant operationPaidAt, Instant refundedAt,
                            boolean refundDateEstimated, boolean afterRelease) {
    }

    /** Partage admin décidé dont le versement au voyageur n'est pas terminé. */
    public record PendingSplitRow(UUID paymentId, UUID travelerId, String currency, BigDecimal travelerPayout,
                                  BigDecimal commission, String status, Instant decidedAt) {
    }

    /**
     * Ce qu'il faut savoir d'un voyageur pour son statut DAC7, sans aucune donnée d'identité ni le NIF : seulement leur
     * présence.
     */
    public record TravelerRow(UUID travelerId, String country, String travelerStatus, String businessCountry,
                              boolean taxInfoProvided, boolean identityComplete) {
    }

    /**
     * 1. Carte et mobile money : paiement RELEASED, daté de {@code escrow_released_at}. Net = montant moins commission,
     * plus la part de commission rendue par le bon de parrainage du VOYAGEUR ({@code commission_vouchers} consommé sur
     * le colis, comme {@code ReleaseNetCalculator}), qui n'est donc pas de la commission retenue. Partage admin : compté
     * seulement une fois terminé (COMPLETED), daté de sa fin, net = part du voyageur, commission acquise, sans bon.
     * Voyageur : celui du trajet du colis, ou celui du fil de négociation ; colis du bon : celui du paiement, sinon le
     * colis matérialisé du fil.
     * 2. Espèces : colis CASH livré (COMPLETED), daté de la livraison (repli signalé : dernière mise à jour). Devise du
     * COLIS (celle du fil pour un colis négocié, où net et commission sont fixés). Net = net négocié, sinon poids × prix
     * au kilo du trajet plus les articles de la grille (même base que {@code CashCommissionService}). Commission
     * prélevée au voyageur : {@code cash_commission_charged_amount}, sinon celle du fil (audit
     * {@code CASH_COMMISSION_CHARGED}, seule trace de ce montant), sinon brut moins net négociés, sinon inconnue.
     * 3 et 4. Suppléments et modifications : en ligne, une fois versés ({@code paid_out_at}) ; en espèces, payés (PAID)
     * ou acceptés et colis livré, datés de la livraison.
     */
    private static final String OPERATIONS_SQL = """
            WITH ops AS (
                SELECT 'PAYMENT' AS source, p.id AS source_id,
                       COALESCE(a.traveler_id, nt.traveler_id) AS traveler_id,
                       p.rail AS rail, p.currency AS currency,
                       CASE WHEN ps.id IS NOT NULL THEN ps.traveler_payout_amount
                            ELSE p.amount - p.commission_amount + COALESCE(vt.top_up, 0) END AS net,
                       CASE WHEN ps.id IS NOT NULL THEN p.commission_amount
                            ELSE p.commission_amount - COALESCE(vt.top_up, 0) END AS commission,
                       CASE WHEN ps.id IS NOT NULL
                            THEN COALESCE(ps.completed_at AT TIME ZONE 'UTC', p.escrow_released_at)
                            ELSE p.escrow_released_at END AS paid_at,
                       TRUE AS counts,
                       (ps.id IS NOT NULL AND ps.completed_at IS NULL) AS date_estimated
                FROM payments p
                LEFT JOIN bids b ON b.id = p.bid_id
                LEFT JOIN announcements a ON a.id = b.announcement_id
                LEFT JOIN negotiation_threads nt ON nt.id = p.negotiation_thread_id
                LEFT JOIN payment_splits ps ON ps.payment_id = p.id AND ps.deleted_at IS NULL
                LEFT JOIN LATERAL (
                    SELECT ROUND(p.commission_amount * (1 - cv.factor), 2) AS top_up
                    FROM commission_vouchers cv
                    WHERE cv.user_id = COALESCE(a.traveler_id, nt.traveler_id)
                      AND cv.consumed_on_bid_id = COALESCE(p.bid_id, nt.materialized_bid_id,
                          (SELECT mb.id FROM bids mb WHERE mb.linked_negotiation_thread_id = p.negotiation_thread_id
                           ORDER BY mb.created_at LIMIT 1))
                    ORDER BY cv.consumed_at LIMIT 1) vt ON TRUE
                WHERE p.deleted_at IS NULL AND p.status = 'RELEASED' AND p.escrow_released_at IS NOT NULL
                  AND (ps.id IS NULL OR ps.status = 'COMPLETED')
                UNION ALL
                SELECT 'CASH', b.id, a.traveler_id, 'CASH',
                       CASE WHEN nt.id IS NOT NULL THEN nt.currency ELSE b.currency END,
                       COALESCE(b.negotiated_net_eur,
                                ROUND(COALESCE(b.weight_kg * a.price_per_kg, 0)
                                      + COALESCE((SELECT SUM(i.unit_price_net_snapshot * i.quantity)
                                                  FROM bid_grid_items i WHERE i.bid_id = b.id), 0), 2)),
                       CASE WHEN b.commission_status = 'CHARGED'
                            THEN COALESCE(b.cash_commission_charged_amount, tc.commission,
                                          b.negotiated_gross_eur - b.negotiated_net_eur) END,
                       COALESCE(b.delivered_at AT TIME ZONE 'UTC', b.updated_at),
                       TRUE,
                       b.delivered_at IS NULL
                FROM bids b
                JOIN announcements a ON a.id = b.announcement_id
                LEFT JOIN negotiation_threads nt ON nt.id = b.linked_negotiation_thread_id
                LEFT JOIN LATERAL (
                    SELECT CAST(al.payload ->> 'commission' AS NUMERIC) AS commission
                    FROM audit_log al
                    WHERE al.entity_type = 'NEGOTIATION_THREAD' AND al.entity_id = b.linked_negotiation_thread_id
                      AND al.action = 'CASH_COMMISSION_CHARGED'
                    ORDER BY al.created_at DESC, al.id DESC LIMIT 1) tc ON TRUE
                WHERE b.payment_method = 'CASH' AND b.status = 'COMPLETED'
                UNION ALL
                SELECT 'SUPPLEMENT', s.id, s.traveler_id, s.rail, s.currency, s.net_amount, s.commission_amount,
                       CASE WHEN s.rail = 'CASH' THEN COALESCE(b.delivered_at AT TIME ZONE 'UTC', b.updated_at)
                            ELSE s.paid_out_at AT TIME ZONE 'UTC' END,
                       FALSE,
                       s.rail = 'CASH' AND b.delivered_at IS NULL
                FROM bid_supplements s
                JOIN bids b ON b.id = s.bid_id
                WHERE s.deleted_at IS NULL
                  AND ((s.rail <> 'CASH' AND s.paid_out_at IS NOT NULL)
                       OR (s.rail = 'CASH' AND s.status = 'PAID' AND b.status = 'COMPLETED'))
                UNION ALL
                SELECT 'AMENDMENT', m.id, m.traveler_id, m.rail, m.currency, m.net_amount, m.commission_amount,
                       CASE WHEN m.rail = 'CASH' THEN COALESCE(b.delivered_at AT TIME ZONE 'UTC', b.updated_at)
                            ELSE m.paid_out_at AT TIME ZONE 'UTC' END,
                       FALSE,
                       m.rail = 'CASH' AND b.delivered_at IS NULL
                FROM bid_amendments m
                JOIN bids b ON b.id = m.bid_id
                WHERE m.deleted_at IS NULL
                  AND ((m.rail <> 'CASH' AND m.paid_out_at IS NOT NULL)
                       OR (m.rail = 'CASH' AND m.status IN ('ACCEPTED', 'PAYOUT_PENDING', 'PAID_OUT')
                           AND b.status = 'COMPLETED'))
            )
            SELECT * FROM ops
            WHERE paid_at >= :from AND paid_at < :to
            ORDER BY paid_at, source_id
            """;

    /**
     * Remboursements, un par événement. Paiement RELEASED hors partage admin (le partage intègre la part de
     * l'expéditeur) : les audits {@code PAYMENT_PARTIALLY_REFUNDED} (en séquestre) et {@code REFUND_AFTER_RELEASE}
     * (après versement, webhook {@code charge.refunded}) portent le montant remboursé CUMULÉ ({@code refundedAmount}) :
     * la différence avec l'audit précédent donne chaque remboursement, à sa date. Ce que l'audit n'explique pas
     * ({@code refunded_amount} au-delà du dernier cumul audité) sort en une ligne datée de la dernière mise à jour du
     * paiement, estimée, et comptée après versement par prudence. Supplément et modification en ligne : REFUNDED alors
     * que {@code paid_out_at} est posé, après versement. Gardés si le remboursement OU l'opération tombe dans la
     * fenêtre.
     */
    private static final String REFUNDS_SQL = """
            WITH pay AS (
                SELECT p.id, COALESCE(a.traveler_id, nt.traveler_id) AS traveler_id, p.currency, p.refunded_amount,
                       p.amount - p.commission_amount AS operation_net, p.escrow_released_at, p.updated_at
                FROM payments p
                LEFT JOIN bids b ON b.id = p.bid_id
                LEFT JOIN announcements a ON a.id = b.announcement_id
                LEFT JOIN negotiation_threads nt ON nt.id = p.negotiation_thread_id
                WHERE p.deleted_at IS NULL AND p.status = 'RELEASED' AND p.escrow_released_at IS NOT NULL
                  AND p.refunded_amount > 0
                  AND NOT EXISTS (SELECT 1 FROM payment_splits ps
                                  WHERE ps.payment_id = p.id AND ps.deleted_at IS NULL)
            ),
            events AS (
                SELECT pay.id AS payment_id, al.id AS audit_id, al.created_at AS at, al.action,
                       CAST(al.payload ->> 'refundedAmount' AS NUMERIC) AS cumulative
                FROM pay
                JOIN audit_log al ON al.entity_type = 'PAYMENT' AND al.entity_id = pay.id
                 AND al.action IN ('PAYMENT_PARTIALLY_REFUNDED', 'REFUND_AFTER_RELEASE')
                WHERE al.payload ->> 'refundedAmount' IS NOT NULL
            ),
            steps AS (
                SELECT payment_id, at, action, cumulative,
                       cumulative - COALESCE(LAG(cumulative) OVER (PARTITION BY payment_id ORDER BY at, audit_id), 0)
                           AS amount
                FROM events
            ),
            refunds AS (
                SELECT 'PAYMENT' AS source, pay.id AS source_id, pay.traveler_id, pay.currency, s.amount,
                       pay.operation_net, pay.escrow_released_at AS operation_paid_at, s.at AS refunded_at,
                       FALSE AS estimated,
                       (s.action = 'REFUND_AFTER_RELEASE' OR s.at > pay.escrow_released_at) AS after_release
                FROM steps s JOIN pay ON pay.id = s.payment_id
                WHERE s.amount > 0
                UNION ALL
                SELECT 'PAYMENT', pay.id, pay.traveler_id, pay.currency,
                       pay.refunded_amount - COALESCE((SELECT MAX(e.cumulative) FROM events e
                                                       WHERE e.payment_id = pay.id), 0),
                       pay.operation_net, pay.escrow_released_at, pay.updated_at, TRUE, TRUE
                FROM pay
                WHERE pay.refunded_amount > COALESCE((SELECT MAX(e.cumulative) FROM events e
                                                      WHERE e.payment_id = pay.id), 0)
                UNION ALL
                SELECT 'SUPPLEMENT', s.id, s.traveler_id, s.currency, s.net_amount, s.net_amount,
                       s.paid_out_at AT TIME ZONE 'UTC', s.refunded_at AT TIME ZONE 'UTC', FALSE, TRUE
                FROM bid_supplements s
                WHERE s.deleted_at IS NULL AND s.rail <> 'CASH' AND s.status = 'REFUNDED'
                  AND s.paid_out_at IS NOT NULL AND s.refunded_at IS NOT NULL
                UNION ALL
                SELECT 'AMENDMENT', m.id, m.traveler_id, m.currency, m.net_amount, m.net_amount,
                       m.paid_out_at AT TIME ZONE 'UTC', m.refunded_at AT TIME ZONE 'UTC', FALSE, TRUE
                FROM bid_amendments m
                WHERE m.deleted_at IS NULL AND m.rail <> 'CASH' AND m.status = 'REFUNDED'
                  AND m.paid_out_at IS NOT NULL AND m.refunded_at IS NOT NULL
            )
            SELECT * FROM refunds
            WHERE (refunded_at >= :from AND refunded_at < :to)
               OR (operation_paid_at >= :from AND operation_paid_at < :to)
            ORDER BY refunded_at, source_id
            """;

    /** Partages admin décidés, part du voyageur non nulle, transfert pas encore terminé (état courant, sans fenêtre). */
    private static final String PENDING_SPLITS_SQL = """
            SELECT p.id AS payment_id, COALESCE(a.traveler_id, nt.traveler_id, ps.traveler_id) AS traveler_id,
                   p.currency, ps.traveler_payout_amount, p.commission_amount, ps.status,
                   ps.created_at AT TIME ZONE 'UTC' AS decided_at
            FROM payment_splits ps
            JOIN payments p ON p.id = ps.payment_id
            LEFT JOIN bids b ON b.id = p.bid_id
            LEFT JOIN announcements a ON a.id = b.announcement_id
            LEFT JOIN negotiation_threads nt ON nt.id = p.negotiation_thread_id
            WHERE ps.deleted_at IS NULL AND ps.status <> 'COMPLETED' AND ps.traveler_payout_amount > 0
              AND p.deleted_at IS NULL AND p.status = 'RELEASED'
            ORDER BY ps.created_at, p.id
            """;

    /**
     * Pays, statut et présence des données d'identité, jamais leur valeur ni le NIF. Identité complète : compte non
     * supprimé et, pour un professionnel, raison sociale et immatriculation ; sinon prénom, nom, date de naissance et
     * adresse. Comptes supprimés compris (ils sortent incomplets).
     */
    private static final String TRAVELERS_SQL = """
            SELECT u.id, u.country, u.traveler_status, u.business_country,
                   u.tax_identification_number IS NOT NULL AS tax_provided,
                   (u.deleted_at IS NULL AND CASE
                        WHEN u.traveler_status = 'PROFESSIONAL'
                            THEN u.business_name IS NOT NULL AND u.business_registration_number IS NOT NULL
                        ELSE u.first_name IS NOT NULL AND u.last_name IS NOT NULL AND u.birth_date IS NOT NULL
                             AND u.residence_street IS NOT NULL END) AS identity_complete
            FROM users u
            WHERE u.id IN (:ids)
            """;

    /**
     * Le voyageur a-t-il reçu de l'argent depuis {@code :since} (tous rails) ? Mêmes prédicats que
     * {@link #OPERATIONS_SQL}, un {@code EXISTS} par chemin, chacun partant d'une colonne indexée
     * ({@code announcements.traveler_id}, {@code negotiation_threads.traveler_id}, {@code traveler_id} des suppléments
     * et modifications). SQL portable : aussi exécuté sur H2 par les tests du contexte Spring.
     */
    private static final String HAS_BEEN_PAID_SQL = """
            SELECT CASE WHEN
                EXISTS (SELECT 1 FROM announcements a
                        JOIN bids b ON b.announcement_id = a.id
                        JOIN payments p ON p.bid_id = b.id
                        WHERE a.traveler_id = :id AND p.deleted_at IS NULL AND p.status = 'RELEASED'
                          AND p.escrow_released_at >= :since)
                OR EXISTS (SELECT 1 FROM negotiation_threads nt
                           JOIN payments p ON p.negotiation_thread_id = nt.id
                           WHERE nt.traveler_id = :id AND p.deleted_at IS NULL AND p.status = 'RELEASED'
                             AND p.escrow_released_at >= :since)
                OR EXISTS (SELECT 1 FROM announcements a
                           JOIN bids b ON b.announcement_id = a.id
                           WHERE a.traveler_id = :id AND b.payment_method = 'CASH' AND b.status = 'COMPLETED'
                             AND (b.delivered_at >= :sinceUtc OR (b.delivered_at IS NULL AND b.updated_at >= :since)))
                OR EXISTS (SELECT 1 FROM bid_supplements s
                           WHERE s.traveler_id = :id AND s.deleted_at IS NULL AND s.paid_out_at >= :sinceUtc)
                OR EXISTS (SELECT 1 FROM bid_amendments m
                           WHERE m.traveler_id = :id AND m.deleted_at IS NULL AND m.paid_out_at >= :sinceUtc)
            THEN TRUE ELSE FALSE END
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public Dac7ReportReadModel(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Lignes versées dans {@code [from, to[}. */
    public List<OperationRow> operations(Instant from, Instant to) {
        return jdbc.query(OPERATIONS_SQL, window(from, to), (rs, i) -> new OperationRow(
                Dac7Source.valueOf(rs.getString("source")),
                uuid(rs, "source_id"),
                uuid(rs, "traveler_id"),
                rs.getString("rail"),
                rs.getString("currency"),
                rs.getBigDecimal("net"),
                rs.getBigDecimal("commission"),
                instant(rs, "paid_at"),
                rs.getBoolean("counts"),
                rs.getBoolean("date_estimated")));
    }

    /** Remboursements datés dans {@code [from, to[}, ou dont l'opération l'est. */
    public List<RefundRow> refunds(Instant from, Instant to) {
        return jdbc.query(REFUNDS_SQL, window(from, to), (rs, i) -> new RefundRow(
                Dac7Source.valueOf(rs.getString("source")),
                uuid(rs, "source_id"),
                uuid(rs, "traveler_id"),
                rs.getString("currency"),
                rs.getBigDecimal("amount"),
                rs.getBigDecimal("operation_net"),
                instant(rs, "operation_paid_at"),
                instant(rs, "refunded_at"),
                rs.getBoolean("estimated"),
                rs.getBoolean("after_release")));
    }

    public List<PendingSplitRow> pendingSplits() {
        return jdbc.query(PENDING_SPLITS_SQL, new MapSqlParameterSource(), (rs, i) -> new PendingSplitRow(
                uuid(rs, "payment_id"),
                uuid(rs, "traveler_id"),
                rs.getString("currency"),
                rs.getBigDecimal("traveler_payout_amount"),
                rs.getBigDecimal("commission_amount"),
                rs.getString("status"),
                instant(rs, "decided_at")));
    }

    /** Par paquets de {@link #IN_CHUNK} identifiants. */
    public List<TravelerRow> travelers(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<UUID> all = new ArrayList<>(ids);
        List<TravelerRow> rows = new ArrayList<>();
        for (int start = 0; start < all.size(); start += IN_CHUNK) {
            List<UUID> chunk = all.subList(start, Math.min(all.size(), start + IN_CHUNK));
            rows.addAll(jdbc.query(TRAVELERS_SQL, new MapSqlParameterSource("ids", chunk), (rs, i) -> new TravelerRow(
                    uuid(rs, "id"),
                    rs.getString("country"),
                    rs.getString("traveler_status"),
                    rs.getString("business_country"),
                    rs.getBoolean("tax_provided"),
                    rs.getBoolean("identity_complete"))));
        }
        return rows;
    }

    /** Le voyageur a-t-il déjà reçu de l'argent, toutes dates confondues ? */
    public boolean hasBeenPaid(UUID travelerId) {
        return hasBeenPaidSince(travelerId, Instant.EPOCH);
    }

    /** Le voyageur a-t-il reçu de l'argent depuis {@code since} ? */
    public boolean hasBeenPaidSince(UUID travelerId, Instant since) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", travelerId)
                .addValue("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
                .addValue("sinceUtc", LocalDateTime.ofInstant(since, ZoneOffset.UTC));
        return Boolean.TRUE.equals(jdbc.queryForObject(HAS_BEEN_PAID_SQL, params, Boolean.class));
    }

    private static MapSqlParameterSource window(Instant from, Instant to) {
        return new MapSqlParameterSource()
                .addValue("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .addValue("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
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
