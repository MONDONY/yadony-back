package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminPaymentInsight;
import com.yadony.api.admin.dto.AdminWalletResponse;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Lecture back-office des paiements : recherche filtrée (liste, totaux, export partagent le même
 * {@code WHERE}), et contexte de chaque paiement (colis ou négociation, expéditeur, voyageur,
 * trajet, liens Stripe). Lecture seule : aucun geste sur l'argent ici.
 */
@Service
public class AdminPaymentInsights {

    /** Un paiement PENDING plus vieux que ça est un checkout abandonné. */
    static final Duration ABANDONED_AFTER = Duration.ofHours(24);

    /** Plafond de l'export CSV : au-delà, l'admin resserre la période. */
    public static final int EXPORT_MAX_ROWS = 10_000;

    /** Les parties d'un paiement, colis classique ou fil de négociation (colis lié compris). */
    private static final String PARTIES_OF_P = """
            SELECT b.sender_id FROM bids b
             WHERE b.id = p.bid_id
                OR (p.bid_id IS NULL AND p.negotiation_thread_id IS NOT NULL
                    AND b.linked_negotiation_thread_id = p.negotiation_thread_id)
            UNION
            SELECT a.traveler_id FROM bids b JOIN announcements a ON a.id = b.announcement_id
             WHERE b.id = p.bid_id
                OR (p.bid_id IS NULL AND p.negotiation_thread_id IS NOT NULL
                    AND b.linked_negotiation_thread_id = p.negotiation_thread_id)
            UNION
            SELECT r.sender_id FROM negotiation_threads t JOIN package_requests r ON r.id = t.package_request_id
             WHERE t.id = p.negotiation_thread_id
            UNION
            SELECT t.traveler_id FROM negotiation_threads t WHERE t.id = p.negotiation_thread_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final PaymentRepository paymentRepository;
    private final boolean stripeLive;

    public AdminPaymentInsights(NamedParameterJdbcTemplate jdbc, PaymentRepository paymentRepository,
                                @Value("${stripe.secret-key:}") String stripeSecretKey) {
        this.jdbc = jdbc;
        this.paymentRepository = paymentRepository;
        String key = stripeSecretKey == null ? "" : stripeSecretKey;
        this.stripeLive = key.startsWith("sk_live") || key.startsWith("rk_live");
    }

    // ── Recherche ───────────────────────────────────────────────────────────────

    /** Page de paiements correspondant au filtre, du plus récent au plus ancien. */
    public Page<PaymentEntity> search(AdminPaymentFilter filter, Pageable pageable) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(filter, params);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM payments p " + where, params, Long.class);
        params.addValue("limit", pageable.getPageSize());
        params.addValue("offset", pageable.getOffset());
        List<UUID> ids = jdbc.query("SELECT CAST(p.id AS VARCHAR(36)) FROM payments p " + where
                        + " ORDER BY p.created_at DESC, p.id DESC LIMIT :limit OFFSET :offset",
                params, (rs, i) -> UUID.fromString(rs.getString(1)));
        return new PageImpl<>(load(ids), pageable, total == null ? 0 : total);
    }

    /** Paiements de l'export, dans l'ordre de la liste, au plus {@link #EXPORT_MAX_ROWS}. */
    public List<PaymentEntity> exportRows(AdminPaymentFilter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource("limit", EXPORT_MAX_ROWS);
        String where = where(filter, params);
        List<UUID> ids = jdbc.query("SELECT CAST(p.id AS VARCHAR(36)) FROM payments p " + where
                        + " ORDER BY p.created_at DESC, p.id DESC LIMIT :limit",
                params, (rs, i) -> UUID.fromString(rs.getString(1)));
        return load(ids);
    }

    /** Totaux du périmètre filtré, une ligne par devise (on n'additionne jamais des XOF et des EUR). */
    public List<CurrencyTotals> totals(AdminPaymentFilter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(filter, params);
        return jdbc.query("""
                SELECT UPPER(p.currency) AS currency,
                       COUNT(*) AS n,
                       SUM(CASE WHEN p.status = 'ESCROW' THEN p.amount ELSE 0 END) AS escrow,
                       SUM(CASE WHEN p.status = 'RELEASED' THEN p.amount ELSE 0 END) AS released,
                       SUM(COALESCE(p.refunded_amount, 0)) AS refunded,
                       SUM(CASE WHEN p.status IN ('ESCROW', 'RELEASED') THEN p.commission_amount ELSE 0 END) AS commission,
                       SUM(CASE WHEN p.status = 'PENDING' THEN 1 ELSE 0 END) AS pending
                FROM payments p
                """ + where + " GROUP BY UPPER(p.currency) ORDER BY UPPER(p.currency)",
                params, (rs, i) -> new CurrencyTotals(
                        rs.getString("currency"),
                        rs.getLong("n"),
                        cents(rs.getBigDecimal("escrow")),
                        cents(rs.getBigDecimal("released")),
                        cents(rs.getBigDecimal("refunded")),
                        cents(rs.getBigDecimal("commission")),
                        rs.getLong("pending")));
    }

    /**
     * Totaux d'une devise, montants en centimes comme le reste de l'API admin.
     *
     * @param escrowCents     argent encore chez Yadony (séquestre)
     * @param releasedCents   argent versé aux voyageurs (montant brut des paiements libérés)
     * @param commissionCents commission des paiements en séquestre ou libérés
     * @param pendingCount    paiements jamais aboutis (checkout non terminé)
     */
    public record CurrencyTotals(String currency, long count, long escrowCents, long releasedCents,
                                 long refundedCents, long commissionCents, long pendingCount) {
    }

    private String where(AdminPaymentFilter f, MapSqlParameterSource params) {
        StringBuilder sql = new StringBuilder("WHERE p.deleted_at IS NULL");
        if (f.status() != null) {
            sql.append(" AND p.status = :status");
            params.addValue("status", f.status());
        }
        if (f.from() != null) {
            sql.append(" AND p.created_at >= :from");
            params.addValue("from", Timestamp.valueOf(f.from()));
        }
        if (f.to() != null) {
            sql.append(" AND p.created_at <= :to");
            params.addValue("to", Timestamp.valueOf(f.to()));
        }
        if (f.rail() != null) {
            sql.append(" AND p.rail = :rail");
            params.addValue("rail", f.rail());
        }
        if (f.currency() != null) {
            sql.append(" AND UPPER(p.currency) = :currency");
            params.addValue("currency", f.currency());
        }
        if (f.held()) {
            sql.append(" AND p.status = 'ESCROW' AND p.payout_held_at IS NOT NULL");
        }
        if (f.hideAbandoned()) {
            sql.append(" AND NOT (p.status = 'PENDING' AND p.created_at < :abandonedBefore)");
            params.addValue("abandonedBefore", Timestamp.valueOf(abandonedBefore()));
        }
        if (f.query() != null) {
            appendSearch(sql, params, f.query());
        }
        return sql.toString();
    }

    /**
     * Identifiant → paiement, colis (direct ou lié à la négociation) ou fil ; {@code pi_}/{@code ch_}
     * → référence Stripe ; sinon nom, prénom ou pseudo de l'expéditeur ou du voyageur.
     */
    private static void appendSearch(StringBuilder sql, MapSqlParameterSource params, String query) {
        UUID uuid = parseUuid(query);
        if (uuid != null) {
            sql.append(" ").append("""
                    AND (p.id = :qId OR p.bid_id = :qId OR p.negotiation_thread_id = :qId
                          OR EXISTS (SELECT 1 FROM bids lb WHERE lb.id = :qId AND p.negotiation_thread_id IS NOT NULL
                                     AND lb.linked_negotiation_thread_id = p.negotiation_thread_id))""");
            params.addValue("qId", uuid);
            return;
        }
        if (query.startsWith("pi_") || query.startsWith("ch_") || query.startsWith("mm_")) {
            sql.append(" AND (p.stripe_payment_intent_id = :qRef OR p.stripe_charge_id = :qRef)");
            params.addValue("qRef", query);
            return;
        }
        // % et _ retirés : la saisie est un nom, pas un motif LIKE.
        String needle = query.toLowerCase(Locale.ROOT).replace("%", "").replace("_", "").replace("@", "").strip();
        sql.append(" AND EXISTS (SELECT 1 FROM users u WHERE u.id IN (").append(PARTIES_OF_P).append("""
                ) AND (LOWER(u.username) LIKE :qLike
                       OR LOWER(CONCAT(COALESCE(u.first_name, ''), ' ', COALESCE(u.last_name, ''))) LIKE :qLike))""");
        params.addValue("qLike", "%" + needle + "%");
    }

    private static UUID parseUuid(String value) {
        try {
            return value.length() == 36 ? UUID.fromString(value) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private List<PaymentEntity> load(List<UUID> orderedIds) {
        if (orderedIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, PaymentEntity> byId = paymentRepository.findAllById(orderedIds).stream()
                .collect(Collectors.toMap(PaymentEntity::getId, Function.identity(), (a, b) -> a));
        return orderedIds.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    // ── Contexte ────────────────────────────────────────────────────────────────

    /** Contexte de chaque paiement, en deux requêtes quel que soit le nombre de paiements. */
    public Map<UUID, AdminPaymentInsight> insightsOf(Collection<PaymentEntity> payments) {
        List<UUID> ids = payments.stream().map(PaymentEntity::getId).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Links> links = linksOf(ids);
        Set<UUID> partyIds = new LinkedHashSet<>();
        links.values().forEach(l -> {
            if (l.senderId() != null) partyIds.add(l.senderId());
            if (l.travelerId() != null) partyIds.add(l.travelerId());
        });
        Map<UUID, String> names = namesOf(partyIds);
        Map<UUID, AdminPaymentInsight> result = new HashMap<>();
        for (PaymentEntity p : payments) {
            if (p.getId() == null) continue;
            Links l = links.getOrDefault(p.getId(), Links.EMPTY);
            result.put(p.getId(), insight(p, l, names));
        }
        return result;
    }

    public AdminPaymentInsight insightOf(PaymentEntity payment) {
        return insightsOf(List.of(payment)).get(payment.getId());
    }

    private AdminPaymentInsight insight(PaymentEntity p, Links l, Map<UUID, String> names) {
        long net = AdminWalletResponse.toCents(p.getAmount()) - AdminWalletResponse.toCents(p.getCommissionAmount());
        return new AdminPaymentInsight(
                p.getNegotiationThreadId() != null && p.getBidId() == null ? "NEGOTIATION" : "BID",
                p.getBidId() != null ? p.getBidId() : l.bidId(),
                p.getNegotiationThreadId(),
                party(l.senderId(), names),
                party(l.travelerId(), names),
                l.departureCity(),
                l.arrivalCity(),
                l.bidStatus(),
                isAbandoned(p),
                net,
                p.getCapturedAt(),
                p.getFxExchangeRate(),
                p.getStripeChargeId(),
                stripeDashboardUrl(p));
    }

    static boolean isAbandoned(PaymentEntity p) {
        return p.getStatus() == PaymentStatus.PENDING && p.getCreatedAt() != null
                && p.getCreatedAt().isBefore(abandonedBefore());
    }

    private static LocalDateTime abandonedBefore() {
        return LocalDateTime.now(ZoneOffset.UTC).minus(ABANDONED_AFTER);
    }

    String stripeDashboardUrl(PaymentEntity p) {
        String pi = p.getStripePaymentIntentId();
        if (p.getRail() != PaymentRail.STRIPE || pi == null || !pi.startsWith("pi_")) {
            return null;
        }
        return "https://dashboard.stripe.com/" + (stripeLive ? "" : "test/") + "payments/" + pi;
    }

    private static AdminPaymentInsight.Party party(UUID id, Map<UUID, String> names) {
        return id == null ? null : new AdminPaymentInsight.Party(id, names.get(id));
    }

    private record Links(UUID bidId, UUID senderId, UUID travelerId, String departureCity, String arrivalCity,
                         String bidStatus) {
        static final Links EMPTY = new Links(null, null, null, null, null, null);
    }

    /**
     * Colis direct ({@code bid_id}) ou colis lié au fil ; à défaut, la demande et le voyageur du
     * fil — un paiement de négociation a déjà ses deux parties avant que le colis n'existe.
     */
    private Map<UUID, Links> linksOf(List<UUID> ids) {
        Map<UUID, Links> result = new HashMap<>();
        jdbc.query("""
                SELECT CAST(p.id AS VARCHAR(36)) AS pid,
                       CAST(COALESCE(b.id, lb.id) AS VARCHAR(36)) AS bid_id,
                       CAST(COALESCE(b.sender_id, lb.sender_id, r.sender_id) AS VARCHAR(36)) AS sender_id,
                       CAST(COALESCE(a.traveler_id, la.traveler_id, t.traveler_id) AS VARCHAR(36)) AS traveler_id,
                       COALESCE(a.departure_city, la.departure_city, r.departure_city) AS departure_city,
                       COALESCE(a.arrival_city, la.arrival_city, r.arrival_city) AS arrival_city,
                       COALESCE(b.status, lb.status) AS bid_status
                FROM payments p
                LEFT JOIN bids b ON b.id = p.bid_id
                LEFT JOIN announcements a ON a.id = b.announcement_id
                LEFT JOIN negotiation_threads t ON t.id = p.negotiation_thread_id
                LEFT JOIN package_requests r ON r.id = t.package_request_id
                LEFT JOIN bids lb ON p.bid_id IS NULL AND p.negotiation_thread_id IS NOT NULL
                                  AND lb.linked_negotiation_thread_id = p.negotiation_thread_id
                LEFT JOIN announcements la ON la.id = lb.announcement_id
                WHERE p.id IN (:ids)
                """, new MapSqlParameterSource("ids", ids), rs -> {
            UUID pid = UUID.fromString(rs.getString("pid"));
            // Un fil ne matérialise qu'un colis ; en cas de doublon, la première ligne suffit.
            result.putIfAbsent(pid, new Links(uuid(rs.getString("bid_id")), uuid(rs.getString("sender_id")),
                    uuid(rs.getString("traveler_id")), rs.getString("departure_city"),
                    rs.getString("arrival_city"), rs.getString("bid_status")));
        });
        return result;
    }

    /** Nom affiché : « Prénom Nom (@pseudo) », ou ce qui en existe. */
    Map<UUID, String> namesOf(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> names = new HashMap<>();
        jdbc.query("SELECT CAST(id AS VARCHAR(36)) AS id, first_name, last_name, username FROM users WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", new ArrayList<>(userIds)), rs -> {
                    String full = (Objects.toString(rs.getString("first_name"), "") + " "
                            + Objects.toString(rs.getString("last_name"), "")).strip();
                    String username = rs.getString("username");
                    String label = full.isEmpty() ? (username == null ? null : "@" + username)
                            : (username == null ? full : full + " (@" + username + ")");
                    names.put(UUID.fromString(rs.getString("id")), label);
                });
        return names;
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static long cents(BigDecimal value) {
        return AdminWalletResponse.toCents(value);
    }
}
