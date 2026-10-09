package com.yadony.api.payments.overview;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Read-model SQL de l'aperçu « Mon argent » (FLUTTER-HV) : une seule requête, en lecture seule.
 *
 * <p>Pourquoi une requête plutôt que des services : les données vivent dans plusieurs packages
 * (colis et trajets dans {@code matching}, garde « destinataire absent » dans
 * {@code cancellation}, litiges dans {@code disputes}). CLAUDE.md interdit d'injecter leurs
 * services ici ; un event Spring ne convient pas à une lecture synchrone. Le package
 * {@code payments} lit donc ces tables en SQL, exactement comme le font déjà
 * {@code PaymentRepository#findBeneficiaries}, {@code countHeldEscrowForTraveler} et
 * {@code integrity.MoneyInvariants}. Aucune écriture, aucune règle métier d'un autre package
 * n'est dupliquée ici : les prédicats reprennent mot pour mot ceux de
 * {@code CancellationRepository#findDueUnclaimedHolds} et de {@code DeliveryEventListener}.
 *
 * <p>Performance : les colis de l'utilisateur sont pris par {@code idx_announcements_traveler_id}
 * et {@code idx_bids_sender_id}, puis le paiement par {@code idx_payments_bid_id} ou l'unique de
 * {@code payments.negotiation_thread_id} ; les sous-requêtes corrélées passent par
 * {@code idx_disputes_bid_id}, {@code idx_cancellations_bid_id} et {@code idx_pawapay_ops_payment}.
 * Pas de N+1 : un aller-retour pour toutes les lignes, borné par {@link #MAX_ROWS}.
 *
 * <p>Ne filtre volontairement pas {@code deleted_at} sur les colis et trajets : un argent en
 * séquestre ne doit jamais disparaître de la vue de son propriétaire parce que le trajet a été
 * retiré. Les paiements supprimés ({@code payments.deleted_at}) sont exclus.
 */
@Component
public class MoneyOverviewReadModel {

    /** Plafond de lignes renvoyées : la vue n'est pas un relevé exhaustif. */
    static final int MAX_ROWS = 200;

    private static final String SQL = """
            WITH my_bids AS (
                SELECT 'TRAVELER' AS role, b.id AS bid_id, b.status AS bid_status,
                       b.payment_method AS bid_payment_method, b.currency AS bid_currency,
                       b.tracking_number, b.weight_kg, b.commission_status, b.linked_negotiation_thread_id,
                       a.id AS announcement_id, a.departure_city, a.arrival_city, a.departure_date,
                       b.sender_id AS counterparty_id
                FROM bids b
                JOIN announcements a ON a.id = b.announcement_id
                WHERE a.traveler_id = :userId
                UNION ALL
                SELECT 'SENDER' AS role, b.id, b.status, b.payment_method, b.currency,
                       b.tracking_number, b.weight_kg, b.commission_status, b.linked_negotiation_thread_id,
                       a.id, a.departure_city, a.arrival_city, a.departure_date,
                       a.traveler_id
                FROM bids b
                JOIN announcements a ON a.id = b.announcement_id
                WHERE b.sender_id = :userId
            )
            SELECT mb.role, mb.bid_id, mb.bid_status, mb.bid_payment_method, mb.bid_currency,
                   mb.tracking_number, mb.weight_kg, mb.commission_status, mb.announcement_id, mb.departure_city, mb.arrival_city,
                   mb.departure_date, mb.counterparty_id,
                   p.id AS payment_id, p.status AS payment_status, p.rail, p.amount, p.commission_amount,
                   p.refunded_amount, p.currency AS payment_currency, p.disputed, p.payout_held_at,
                   p.escrow_released_at, p.updated_at AS payment_updated_at,
                   (SELECT COUNT(*) FROM disputes d
                     WHERE d.bid_id = mb.bid_id AND d.deleted_at IS NULL AND d.status <> 'RESOLVED') AS open_disputes,
                   (SELECT COUNT(*) FROM disputes d
                     WHERE d.bid_id = mb.bid_id AND d.deleted_at IS NULL) AS all_disputes,
                   (SELECT MIN(c.hold_until) FROM cancellations c
                     WHERE c.bid_id = mb.bid_id AND c.deleted_at IS NULL AND c.scope = 'DELIVERY'
                       AND c.reason = 'RECIPIENT_NO_SHOW'
                       AND c.no_show_status IN ('PENDING_CONFIRMATION', 'CONFIRMED')
                       AND c.hold_until IS NOT NULL AND c.unclaimed_at IS NULL) AS hold_until,
                   (SELECT COUNT(*) FROM pawapay_operations o
                     WHERE p.id IS NOT NULL AND o.payment_id = p.id AND o.kind = 'PAYOUT'
                       AND o.status NOT IN ('COMPLETED', 'FAILED', 'SUBMIT_REJECTED')) AS open_payouts,
                   (SELECT COUNT(*) FROM pawapay_operations o
                     WHERE p.id IS NOT NULL AND o.payment_id = p.id AND o.kind = 'REFUND'
                       AND o.status NOT IN ('COMPLETED', 'FAILED', 'SUBMIT_REJECTED')) AS open_refunds
            FROM my_bids mb
            LEFT JOIN payments p
                   ON p.deleted_at IS NULL
                  AND (p.bid_id = mb.bid_id
                       OR (p.bid_id IS NULL AND mb.linked_negotiation_thread_id IS NOT NULL
                           AND p.negotiation_thread_id = mb.linked_negotiation_thread_id))
            WHERE (p.id IS NOT NULL
                   AND (p.status = 'ESCROW'
                        OR (p.status IN ('RELEASED', 'REFUNDED')
                            AND COALESCE(p.escrow_released_at, p.updated_at) >= :since)))
               OR (p.id IS NULL AND mb.bid_payment_method = 'CASH'
                   AND mb.bid_status IN ('ACCEPTED', 'HANDED_OVER', 'IN_TRANSIT', 'ARRIVED'))
            ORDER BY mb.departure_date DESC, mb.bid_id
            LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public MoneyOverviewReadModel(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Colis de {@code userId} (comme voyageur ou comme expéditeur) dont l'argent est en séquestre,
     * libéré ou remboursé depuis {@code since}, ou réglé en espèces et encore en cours.
     */
    public List<MoneyRow> findRows(UUID userId, OffsetDateTime since) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("since", since)
                .addValue("limit", MAX_ROWS);
        return jdbc.query(SQL, params, (rs, i) -> map(rs));
    }

    private static MoneyRow map(ResultSet rs) throws SQLException {
        return new MoneyRow(
                MoneyRole.valueOf(rs.getString("role")),
                uuid(rs, "bid_id"),
                rs.getString("bid_status"),
                rs.getString("bid_payment_method"),
                rs.getString("bid_currency"),
                rs.getString("tracking_number"),
                rs.getBigDecimal("weight_kg"),
                rs.getString("commission_status"),
                uuid(rs, "announcement_id"),
                rs.getString("departure_city"),
                rs.getString("arrival_city"),
                rs.getObject("departure_date", LocalDate.class),
                uuid(rs, "counterparty_id"),
                uuid(rs, "payment_id"),
                rs.getString("payment_status"),
                rs.getString("rail"),
                rs.getBigDecimal("amount"),
                rs.getBigDecimal("commission_amount"),
                rs.getBigDecimal("refunded_amount"),
                rs.getString("payment_currency"),
                rs.getBoolean("disputed"),
                rs.getObject("payout_held_at", LocalDateTime.class),
                rs.getObject("escrow_released_at", OffsetDateTime.class),
                rs.getObject("payment_updated_at", OffsetDateTime.class),
                rs.getLong("open_disputes"),
                rs.getLong("all_disputes"),
                rs.getObject("hold_until", OffsetDateTime.class),
                rs.getLong("open_payouts"),
                rs.getLong("open_refunds"));
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        return value instanceof UUID u ? u : UUID.fromString(value.toString());
    }
}
