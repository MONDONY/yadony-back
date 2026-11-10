package com.yadony.api.dac7;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Lecture SQL, seule, des montants versés aux voyageurs pour DAC7. Même choix que
 * {@code payments.overview.MoneyOverviewReadModel} : les données vivent dans plusieurs paquets (paiements,
 * colis, suppléments, modifications, fils de négociation) et CLAUDE.md interdit d'injecter leurs services ici ; un
 * événement ne convient pas à une lecture synchrone. Aucune écriture, aucune règle métier recopiée : les prédicats
 * reprennent ceux des écrans d'argent et de la sonde d'intégrité.
 *
 * <p>NE PAS remplacer par {@code FiscalExportService} : il additionne {@code amount} sans regarder la devise et ignore
 * les espèces.
 *
 * <p>Dates : les colonnes sans fuseau ({@code bids.delivered_at}, {@code paid_out_at}, {@code refunded_at}) sont écrites
 * en UTC par l'application et lues {@code AT TIME ZONE 'UTC'}, comme le fait {@code MoneyInvariants}. Les lignes
 * supprimées d'un colis ou d'un trajet ne sont PAS écartées : l'argent versé reste un fait fiscal ; seuls les paiements,
 * suppléments et modifications supprimés (lignes de test ou annulées) le sont, comme le font leurs entités.
 */
@Component
public class Dac7ReportReadModel {

    /**
     * Une ligne versée au voyageur.
     *
     * @param net               contrepartie nette de la commission, dans la devise d'origine
     * @param commission        commission de Yadony ; {@code null} si elle est introuvable (espèces anciennes)
     * @param paidAt            versement (carte, mobile money) ou livraison (espèces)
     * @param countsAsOperation vrai pour un colis payé (paiement ou espèces), faux pour un supplément ou une modification
     */
    public record OperationRow(Dac7Source source, UUID sourceId, UUID travelerId, String rail, String currency,
                               BigDecimal net, BigDecimal commission, Instant paidAt, boolean countsAsOperation) {
    }

    /**
     * Un remboursement à l'expéditeur après versement au voyageur.
     *
     * @param amount              montant remboursé, dans la devise d'origine
     * @param operationNet        net de l'opération remboursée (plafond de la déduction)
     * @param refundDateEstimated vrai si la date vient de la dernière mise à jour du paiement, faute d'audit
     */
    public record RefundRow(Dac7Source source, UUID sourceId, UUID travelerId, String currency, BigDecimal amount,
                            BigDecimal operationNet, Instant operationPaidAt, Instant refundedAt,
                            boolean refundDateEstimated) {
    }

    /** Ce qu'il faut savoir d'un voyageur pour son statut DAC7, sans aucune donnée d'identité ni le NIF. */
    public record TravelerRow(UUID travelerId, String country, String travelerStatus, String businessCountry,
                              boolean taxInfoProvided) {
    }

    /**
     * 1. Carte et mobile money : paiement RELEASED, daté de {@code escrow_released_at}. Net = montant moins commission ;
     * pour un partage admin (litige, destinataire absent), net = part du voyageur ({@code traveler_payout_amount}), la
     * commission restant acquise. Voyageur : celui du trajet du colis, ou celui du fil de négociation.
     * 2. Espèces : colis CASH livré (COMPLETED), daté de la livraison. Net = net négocié, sinon poids × prix au kilo du
     * trajet plus les articles de la grille (même base que {@code CashCommissionService}) ; commission = celle prélevée
     * ({@code cash_commission_charged_amount}), sinon brut négocié moins net, sinon inconnue.
     * 3 et 4. Suppléments et modifications : en ligne, une fois versés ({@code paid_out_at}) ; en espèces, payés
     * (PAID) ou acceptés et colis livré, datés de la livraison.
     */
    private static final String OPERATIONS_SQL = """
            WITH ops AS (
                SELECT 'PAYMENT' AS source, p.id AS source_id,
                       COALESCE(a.traveler_id, nt.traveler_id) AS traveler_id,
                       p.rail AS rail, p.currency AS currency,
                       COALESCE(ps.traveler_payout_amount, p.amount - p.commission_amount) AS net,
                       p.commission_amount AS commission,
                       p.escrow_released_at AS paid_at,
                       TRUE AS counts
                FROM payments p
                LEFT JOIN bids b ON b.id = p.bid_id
                LEFT JOIN announcements a ON a.id = b.announcement_id
                LEFT JOIN negotiation_threads nt ON nt.id = p.negotiation_thread_id
                LEFT JOIN payment_splits ps ON ps.payment_id = p.id AND ps.deleted_at IS NULL
                WHERE p.deleted_at IS NULL AND p.status = 'RELEASED' AND p.escrow_released_at IS NOT NULL
                UNION ALL
                SELECT 'CASH', b.id, a.traveler_id, 'CASH', a.currency,
                       COALESCE(b.negotiated_net_eur,
                                ROUND(COALESCE(b.weight_kg * a.price_per_kg, 0)
                                      + COALESCE((SELECT SUM(i.unit_price_net_snapshot * i.quantity)
                                                  FROM bid_grid_items i WHERE i.bid_id = b.id), 0), 2)),
                       CASE WHEN b.commission_status = 'CHARGED'
                            THEN COALESCE(b.cash_commission_charged_amount,
                                          b.negotiated_gross_eur - b.negotiated_net_eur) END,
                       COALESCE(b.delivered_at AT TIME ZONE 'UTC', b.updated_at),
                       TRUE
                FROM bids b
                JOIN announcements a ON a.id = b.announcement_id
                WHERE b.payment_method = 'CASH' AND b.status = 'COMPLETED'
                UNION ALL
                SELECT 'SUPPLEMENT', s.id, s.traveler_id, s.rail, s.currency, s.net_amount, s.commission_amount,
                       CASE WHEN s.rail = 'CASH' THEN COALESCE(b.delivered_at AT TIME ZONE 'UTC', b.updated_at)
                            ELSE s.paid_out_at AT TIME ZONE 'UTC' END,
                       FALSE
                FROM bid_supplements s
                JOIN bids b ON b.id = s.bid_id
                WHERE s.deleted_at IS NULL
                  AND ((s.rail <> 'CASH' AND s.paid_out_at IS NOT NULL)
                       OR (s.rail = 'CASH' AND s.status = 'PAID' AND b.status = 'COMPLETED'))
                UNION ALL
                SELECT 'AMENDMENT', m.id, m.traveler_id, m.rail, m.currency, m.net_amount, m.commission_amount,
                       CASE WHEN m.rail = 'CASH' THEN COALESCE(b.delivered_at AT TIME ZONE 'UTC', b.updated_at)
                            ELSE m.paid_out_at AT TIME ZONE 'UTC' END,
                       FALSE
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
     * Remboursements après versement. Paiement : RELEASED avec {@code refunded_amount} positif hors partage admin (le
     * partage intègre déjà la part de l'expéditeur), daté par l'audit {@code REFUND_AFTER_RELEASE} (webhook Stripe
     * {@code charge.refunded}), sinon par la dernière mise à jour du paiement (date estimée). Supplément et modification
     * en ligne : REFUNDED alors que {@code paid_out_at} est posé, daté de {@code refunded_at}. Gardés si le
     * remboursement OU l'opération tombe dans la fenêtre : le service range chacun (déduit ou rectificatif).
     */
    private static final String REFUNDS_SQL = """
            WITH refunds AS (
                SELECT 'PAYMENT' AS source, p.id AS source_id,
                       COALESCE(a.traveler_id, nt.traveler_id) AS traveler_id,
                       p.currency AS currency, p.refunded_amount AS amount,
                       p.amount - p.commission_amount AS operation_net,
                       p.escrow_released_at AS operation_paid_at,
                       COALESCE(ar.refunded_at, p.updated_at) AS refunded_at,
                       ar.refunded_at IS NULL AS estimated
                FROM payments p
                LEFT JOIN bids b ON b.id = p.bid_id
                LEFT JOIN announcements a ON a.id = b.announcement_id
                LEFT JOIN negotiation_threads nt ON nt.id = p.negotiation_thread_id
                LEFT JOIN (SELECT al.entity_id, MAX(al.created_at) AS refunded_at
                           FROM audit_log al
                           WHERE al.entity_type = 'PAYMENT' AND al.action = 'REFUND_AFTER_RELEASE'
                           GROUP BY al.entity_id) ar ON ar.entity_id = p.id
                WHERE p.deleted_at IS NULL AND p.status = 'RELEASED' AND p.escrow_released_at IS NOT NULL
                  AND p.refunded_amount > 0
                  AND NOT EXISTS (SELECT 1 FROM payment_splits ps
                                  WHERE ps.payment_id = p.id AND ps.deleted_at IS NULL)
                UNION ALL
                SELECT 'SUPPLEMENT', s.id, s.traveler_id, s.currency, s.net_amount, s.net_amount,
                       s.paid_out_at AT TIME ZONE 'UTC', s.refunded_at AT TIME ZONE 'UTC', FALSE
                FROM bid_supplements s
                WHERE s.deleted_at IS NULL AND s.rail <> 'CASH' AND s.status = 'REFUNDED'
                  AND s.paid_out_at IS NOT NULL AND s.refunded_at IS NOT NULL
                UNION ALL
                SELECT 'AMENDMENT', m.id, m.traveler_id, m.currency, m.net_amount, m.net_amount,
                       m.paid_out_at AT TIME ZONE 'UTC', m.refunded_at AT TIME ZONE 'UTC', FALSE
                FROM bid_amendments m
                WHERE m.deleted_at IS NULL AND m.rail <> 'CASH' AND m.status = 'REFUNDED'
                  AND m.paid_out_at IS NOT NULL AND m.refunded_at IS NOT NULL
            )
            SELECT * FROM refunds
            WHERE (refunded_at >= :from AND refunded_at < :to)
               OR (operation_paid_at >= :from AND operation_paid_at < :to)
            ORDER BY refunded_at, source_id
            """;

    /** Pays et statut seulement : ni nom, ni adresse, ni NIF (sa seule présence). Comptes supprimés compris. */
    private static final String TRAVELERS_SQL = """
            SELECT u.id, u.country, u.traveler_status, u.business_country,
                   u.tax_identification_number IS NOT NULL AS tax_provided
            FROM users u
            WHERE u.id IN (:ids)
            """;

    /**
     * Le voyageur a-t-il déjà reçu de l'argent, tous rails et toutes dates confondus ? Mêmes prédicats que
     * {@link #OPERATIONS_SQL} (paiement versé, colis en espèces livré, supplément ou modification versés), sans
     * fenêtre. Écrit en SQL portable (aussi exécuté sur H2 par les tests du contexte Spring).
     */
    private static final String HAS_BEEN_PAID_SQL = """
            SELECT CASE WHEN
                EXISTS (SELECT 1 FROM payments p
                        LEFT JOIN bids b ON b.id = p.bid_id
                        LEFT JOIN announcements a ON a.id = b.announcement_id
                        LEFT JOIN negotiation_threads nt ON nt.id = p.negotiation_thread_id
                        WHERE p.deleted_at IS NULL AND p.status = 'RELEASED' AND p.escrow_released_at IS NOT NULL
                          AND (a.traveler_id = :id OR nt.traveler_id = :id))
                OR EXISTS (SELECT 1 FROM bids b JOIN announcements a ON a.id = b.announcement_id
                           WHERE a.traveler_id = :id AND b.payment_method = 'CASH' AND b.status = 'COMPLETED')
                OR EXISTS (SELECT 1 FROM bid_supplements s
                           WHERE s.traveler_id = :id AND s.deleted_at IS NULL AND s.paid_out_at IS NOT NULL)
                OR EXISTS (SELECT 1 FROM bid_amendments m
                           WHERE m.traveler_id = :id AND m.deleted_at IS NULL AND m.paid_out_at IS NOT NULL)
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
                rs.getBoolean("counts")));
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
                rs.getBoolean("estimated")));
    }

    public List<TravelerRow> travelers(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query(TRAVELERS_SQL, new MapSqlParameterSource("ids", ids), (rs, i) -> new TravelerRow(
                uuid(rs, "id"),
                rs.getString("country"),
                rs.getString("traveler_status"),
                rs.getString("business_country"),
                rs.getBoolean("tax_provided")));
    }

    public boolean hasBeenPaid(UUID travelerId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(HAS_BEEN_PAID_SQL,
                new MapSqlParameterSource("id", travelerId), Boolean.class));
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
