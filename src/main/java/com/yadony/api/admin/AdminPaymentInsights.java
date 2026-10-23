package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminPaymentCountsResponse;
import com.yadony.api.admin.dto.AdminPaymentInsight;
import com.yadony.api.admin.dto.AdminPaymentListItemResponse;
import com.yadony.api.payments.hold.PayoutHoldStatus;
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
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
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
    static final Duration ABANDONED_AFTER = Duration.ofHours(AdminPaymentSql.ABANDONED_AFTER_HOURS);

    /** Plafond de l'export CSV : au-delà, l'admin resserre la période. */
    public static final int EXPORT_MAX_ROWS = 10_000;

    /** Le voyageur bénéficiaire d'un paiement : annonce du colis (direct ou lié au fil), ou voyageur du fil. */
    private static final String BENEFICIARY_OF_P = """
            SELECT a.traveler_id FROM bids b JOIN announcements a ON a.id = b.announcement_id
             WHERE b.id = p.bid_id
                OR (p.bid_id IS NULL AND p.negotiation_thread_id IS NOT NULL
                    AND b.linked_negotiation_thread_id = p.negotiation_thread_id)
            UNION
            SELECT t.traveler_id FROM negotiation_threads t WHERE t.id = p.negotiation_thread_id
            """;

    /** Les parties d'un paiement, colis classique ou fil de négociation (colis lié compris). */
    private static final String PARTIES_OF_P = """
            SELECT b.sender_id FROM bids b
             WHERE b.id = p.bid_id
                OR (p.bid_id IS NULL AND p.negotiation_thread_id IS NOT NULL
                    AND b.linked_negotiation_thread_id = p.negotiation_thread_id)
            UNION
            SELECT r.sender_id FROM negotiation_threads t JOIN package_requests r ON r.id = t.package_request_id
             WHERE t.id = p.negotiation_thread_id
            UNION
            """ + BENEFICIARY_OF_P;

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

    /**
     * Page de paiements correspondant au filtre, dans l'ordre de la vue ({@link AdminPaymentSql#ORDER_BY}) :
     * « À surveiller » par alerte la plus urgente puis plus ancien d'abord, sinon plus récent d'abord.
     * {@code now} (UTC) fixe les seuils d'âge (séquestre de 48 h, versés récemment, checkout abandonné).
     */
    public Page<PaymentEntity> search(AdminPaymentFilter filter, LocalDateTime now, Pageable pageable) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(filter, params, now);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM" + AdminPaymentSql.FROM_JOIN + where, params, Long.class);
        params.addValue("limit", pageable.getPageSize());
        params.addValue("offset", pageable.getOffset());
        List<UUID> ids = jdbc.query("SELECT CAST(p.id AS VARCHAR(36)) FROM" + AdminPaymentSql.FROM_JOIN + where
                        + " ORDER BY " + AdminPaymentSql.ORDER_BY + " LIMIT :limit OFFSET :offset",
                params, (rs, i) -> UUID.fromString(rs.getString(1)));
        return new PageImpl<>(load(ids), pageable, total == null ? 0 : total);
    }

    /**
     * Rang d'alerte exclusif ({@link AdminPaymentSql#ALERT_RANK}, 0 = aucune) de chaque paiement, en un
     * aller-retour ; voir {@link AdminPaymentAlert#ofRank}.
     */
    public Map<UUID, Integer> alertRanks(Collection<UUID> paymentIds, LocalDateTime now) {
        if (paymentIds.isEmpty()) {
            return Map.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("ids", new ArrayList<>(paymentIds));
        params.addValue("now", Timestamp.valueOf(now));
        Map<UUID, Integer> ranks = new HashMap<>();
        jdbc.query("SELECT CAST(p.id AS VARCHAR(36)) AS pid, " + AdminPaymentSql.ALERT_RANK + " AS alert_rank FROM"
                        + AdminPaymentSql.FROM_JOIN + "WHERE p.id IN (:ids)", params,
                rs -> {
                    ranks.put(UUID.fromString(rs.getString("pid")), rs.getInt("alert_rank"));
                });
        return ranks;
    }

    /** Paiements dont l'utilisateur est une des parties (payeur ou voyageur), du plus récent au plus ancien. */
    public Page<AdminPaymentListItemResponse> searchByUser(UUID userId, Pageable pageable) {
        MapSqlParameterSource params = new MapSqlParameterSource("userId", userId);
        String where = "WHERE p.deleted_at IS NULL AND :userId IN (" + PARTIES_OF_P + ")";
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM payments p " + where, params, Long.class);
        params.addValue("limit", pageable.getPageSize());
        params.addValue("offset", pageable.getOffset());
        List<UUID> ids = jdbc.query("SELECT CAST(p.id AS VARCHAR(36)) FROM payments p " + where
                        + " ORDER BY p.created_at DESC, p.id DESC LIMIT :limit OFFSET :offset",
                params, (rs, i) -> UUID.fromString(rs.getString(1)));
        return new PageImpl<>(toItems(load(ids)), pageable, total == null ? 0 : total);
    }

    private List<AdminPaymentListItemResponse> toItems(List<PaymentEntity> payments) {
        Map<UUID, AdminPaymentInsight> context = insightsOf(payments);
        return payments.stream().map(p -> {
            AdminPaymentInsight insight = context.get(p.getId());
            UUID travelerId = insight != null && insight.traveler() != null ? insight.traveler().id() : null;
            return AdminPaymentListItemResponse.from(p, travelerId, PayoutHoldStatus.NONE).withInsight(insight);
        }).toList();
    }

    /**
     * Frise d'un voyageur : paiements dont il est le bénéficiaire, du dernier événement (création ou
     * versement retenu) le plus récent au plus ancien, au plus {@code limit}. Avec {@code since} non nul,
     * seuls les paiements créés ou retenus depuis cet instant (UTC) ; sinon les plus récents sans borne de date.
     */
    public List<AdminPaymentListItemResponse> recentByBeneficiary(UUID travelerId, LocalDateTime since, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource("userId", travelerId);
        params.addValue("limit", limit);
        String window = "";
        if (since != null) {
            params.addValue("since", Timestamp.from(since.toInstant(java.time.ZoneOffset.UTC)));
            window = " AND (p.created_at >= :since OR p.payout_held_at >= :since)";
        }
        List<UUID> ids = jdbc.query("SELECT CAST(p.id AS VARCHAR(36)) FROM payments p "
                        + "WHERE p.deleted_at IS NULL AND :userId IN (" + BENEFICIARY_OF_P + ")" + window
                        + " ORDER BY CASE WHEN p.payout_held_at IS NOT NULL AND p.payout_held_at > p.created_at"
                        + " THEN p.payout_held_at ELSE p.created_at END DESC, p.id DESC LIMIT :limit",
                params, (rs, i) -> UUID.fromString(rs.getString(1)));
        return toItems(load(ids));
    }

    /**
     * Argent retenu (séquestre avec versement gelé, comme le filtre {@code held}) dont l'utilisateur est le
     * voyageur bénéficiaire : une ligne par devise, jamais additionnées entre devises. Le montant est le montant
     * BRUT du paiement (commission incluse), comme le filtre {@code held} de la liste des transactions.
     */
    public Map<String, BigDecimal> heldAmountByCurrencyOfBeneficiary(UUID travelerId) {
        Map<String, BigDecimal> result = new java.util.TreeMap<>();
        jdbc.query("""
                SELECT UPPER(p.currency) AS currency, SUM(p.amount) AS total
                FROM payments p
                WHERE p.deleted_at IS NULL AND p.status = 'ESCROW' AND p.payout_held_at IS NOT NULL
                  AND :userId IN (
                """ + BENEFICIARY_OF_P + """
                  )
                GROUP BY UPPER(p.currency)
                """, new MapSqlParameterSource("userId", travelerId),
                rs -> {
                    result.put(rs.getString("currency"), rs.getBigDecimal("total"));
                });
        return result;
    }

    /** Paiements de l'export, dans l'ordre de la liste, au plus {@link #EXPORT_MAX_ROWS}. */
    public List<PaymentEntity> exportRows(AdminPaymentFilter filter, LocalDateTime now) {
        MapSqlParameterSource params = new MapSqlParameterSource("limit", EXPORT_MAX_ROWS);
        String where = where(filter, params, now);
        List<UUID> ids = jdbc.query("SELECT CAST(p.id AS VARCHAR(36)) FROM" + AdminPaymentSql.FROM_JOIN + where
                        + " ORDER BY " + AdminPaymentSql.ORDER_BY + " LIMIT :limit",
                params, (rs, i) -> UUID.fromString(rs.getString(1)));
        return load(ids);
    }

    /**
     * Nombres par vue et par alerte du périmètre du filtre, en un aller-retour ({@link AdminPaymentSql#COUNTS_SELECT}).
     * {@code view}, {@code alert} et {@code status} du filtre sont ignorés ; {@code hideAbandoned} retire les
     * checkouts abandonnés de tous les nombres sauf {@code abandoned}, qui les compte toujours.
     */
    public AdminPaymentCountsResponse counts(AdminPaymentFilter filter, LocalDateTime now) {
        AdminPaymentFilter perimeter = new AdminPaymentFilter(null, filter.from(), filter.to(), filter.rail(),
                filter.currency(), filter.held(), filter.query(), false, AdminPaymentView.ALL, null,
                filter.partyId(), filter.announcementId(), filter.bidId(), filter.amountMin(), filter.amountMax());
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(perimeter, params, now);
        params.addValue("hideAbandoned", filter.hideAbandoned());
        params.addValue("abandonedBefore", Timestamp.valueOf(now.minus(ABANDONED_AFTER)));
        return jdbc.queryForObject(AdminPaymentSql.COUNTS_SELECT + "FROM" + AdminPaymentSql.FROM_JOIN + where, params,
                (rs, i) -> new AdminPaymentCountsResponse(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                        rs.getLong(5), rs.getLong(6), rs.getLong(7),
                        new AdminPaymentCountsResponse.Alerts(rs.getLong(8), rs.getLong(9), rs.getLong(10),
                                rs.getLong(11))));
    }

    /** Totaux du périmètre filtré, une ligne par devise (on n'additionne jamais des XOF et des EUR). */
    public List<CurrencyTotals> totals(AdminPaymentFilter filter, LocalDateTime now) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(filter, params, now);
        return jdbc.query("""
                SELECT UPPER(p.currency) AS currency,
                       COUNT(*) AS n,
                       SUM(CASE WHEN p.status = 'ESCROW' THEN p.amount ELSE 0 END) AS escrow,
                       SUM(CASE WHEN p.status = 'RELEASED' THEN p.amount ELSE 0 END) AS released,
                       SUM(COALESCE(p.refunded_amount, 0)) AS refunded,
                       SUM(CASE WHEN p.status IN ('ESCROW', 'RELEASED') THEN p.commission_amount ELSE 0 END) AS commission,
                       SUM(CASE WHEN p.status = 'PENDING' THEN 1 ELSE 0 END) AS pending
                FROM""" + AdminPaymentSql.FROM_JOIN + where + " GROUP BY UPPER(p.currency) ORDER BY UPPER(p.currency)",
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

    /**
     * {@code WHERE} commun à la liste, aux totaux et à l'export, sur les alias de
     * {@link AdminPaymentSql#FROM_JOIN} (colis, parties et trajet effectifs). Lie toujours {@code :now}
     * et {@code :view}, que {@link AdminPaymentSql#ORDER_BY} lit aussi.
     */
    private String where(AdminPaymentFilter f, MapSqlParameterSource params, LocalDateTime now) {
        AdminPaymentView view = f.view() == null ? AdminPaymentView.ALL : f.view();
        params.addValue("now", Timestamp.valueOf(now));
        params.addValue("view", view.name());
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
            sql.append(" AND ").append(AdminPaymentSql.HELD_PREDICATE);
        }
        if (f.hideAbandoned()) {
            sql.append(" AND NOT ").append(AdminPaymentSql.ABANDONED_PREDICATE);
            params.addValue("abandonedBefore", Timestamp.valueOf(now.minus(ABANDONED_AFTER)));
        }
        if (view != AdminPaymentView.ALL) {
            sql.append(" AND ").append(AdminPaymentSql.VIEW_PREDICATE);
        }
        if (f.alert() != null) {
            sql.append(" AND ").append(AdminPaymentSql.ALERT_FILTER);
            params.addValue("alert", f.alert().name());
        }
        if (f.partyId() != null) {
            sql.append(" AND (").append(AdminPaymentSql.EFF_SENDER_ID).append(" = :partyId OR ")
                    .append(AdminPaymentSql.EFF_TRAVELER_ID).append(" = :partyId)");
            params.addValue("partyId", f.partyId());
        }
        if (f.announcementId() != null) {
            sql.append(" AND ").append(AdminPaymentSql.EFF_ANNOUNCEMENT_ID).append(" = :announcementId");
            params.addValue("announcementId", f.announcementId());
        }
        if (f.bidId() != null) {
            sql.append(" AND ").append(AdminPaymentSql.EFF_BID_ID).append(" = :bidId");
            params.addValue("bidId", f.bidId());
        }
        // Bornes en centimes, incluses ; p.amount est en unités (2 décimales), comme AdminWalletResponse.toCents.
        if (f.amountMin() != null) {
            sql.append(" AND p.amount >= :amountMin");
            params.addValue("amountMin", BigDecimal.valueOf(f.amountMin(), 2));
        }
        if (f.amountMax() != null) {
            sql.append(" AND p.amount <= :amountMax");
            params.addValue("amountMax", BigDecimal.valueOf(f.amountMax(), 2));
        }
        if (f.query() != null) {
            appendSearch(sql, params, f.query());
        }
        return sql.toString();
    }

    /**
     * Recherche libre ({@link AdminPaymentSql#SEARCH_PREDICATE}) : jetons repliés (quatre au plus, le
     * {@code @} d'un pseudo retiré ; une saisie sans jeton utilisable ne trouve rien, voir
     * {@link AdminBidQuery#NO_MATCH_TOKEN}), montant, identifiant UUID exact, référence exacte. Tous les
     * paramètres sont liés, à null s'ils ne s'appliquent pas : le SQL les type par {@code CAST}.
     */
    private static void appendSearch(StringBuilder sql, MapSqlParameterSource params, String query) {
        sql.append(" AND ").append(AdminPaymentSql.SEARCH_PREDICATE);
        List<String> tokens = AdminSearchTerms.tokens(query.replace("@", ""), AdminBidQuery.MAX_TOKENS);
        if (tokens.isEmpty()) {
            // Saisie non vide (« @ », « % », « ' ») sans jeton utilisable : aucun texte ne correspond, jamais tous.
            tokens = List.of(AdminBidQuery.NO_MATCH_TOKEN);
        }
        for (int i = 0; i < AdminBidQuery.MAX_TOKENS; i++) {
            params.addValue("t" + (i + 1), i < tokens.size() ? tokens.get(i) : null);
        }
        OptionalLong amount = AdminSearchTerms.amountCents(query);
        params.addValue("amountCents", amount.isPresent() ? amount.getAsLong() : null);
        UUID id = AdminSearchTerms.uuid(query);
        params.addValue("idTerm", id == null ? null : id.toString());
        params.addValue("refTerm", query);
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
        return insightsOf(payments, LocalDateTime.now(ZoneOffset.UTC));
    }

    /** Comme {@link #insightsOf(Collection)}, le seuil d'abandon étant mesuré à {@code now} (UTC). */
    public Map<UUID, AdminPaymentInsight> insightsOf(Collection<PaymentEntity> payments, LocalDateTime now) {
        Map<UUID, AdminPaymentInsight> result = new HashMap<>();
        contextsOf(payments, now).forEach((id, c) -> result.put(id, c.insight()));
        return result;
    }

    /**
     * Contexte d'une ligne de la liste : le contexte du paiement, plus le n° de suivi du colis (direct ou lié
     * au fil) et l'annonce du trajet ; {@code null} pour ce qui est inconnu (négociation sans colis).
     */
    public record Context(AdminPaymentInsight insight, String trackingNumber, UUID announcementId) {
    }

    /** Contexte enrichi de chaque paiement, dans les mêmes deux requêtes que {@link #insightsOf}. */
    public Map<UUID, Context> contextsOf(Collection<PaymentEntity> payments, LocalDateTime now) {
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
        Map<UUID, Context> result = new HashMap<>();
        for (PaymentEntity p : payments) {
            if (p.getId() == null) continue;
            Links l = links.getOrDefault(p.getId(), Links.EMPTY);
            result.put(p.getId(), new Context(insight(p, l, names, now), l.trackingNumber(), l.announcementId()));
        }
        return result;
    }

    public AdminPaymentInsight insightOf(PaymentEntity payment) {
        return insightsOf(List.of(payment)).get(payment.getId());
    }

    private AdminPaymentInsight insight(PaymentEntity p, Links l, Map<UUID, String> names, LocalDateTime now) {
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
                isAbandoned(p, now),
                net,
                p.getCapturedAt(),
                p.getFxExchangeRate(),
                p.getStripeChargeId(),
                stripeDashboardUrl(p));
    }

    static boolean isAbandoned(PaymentEntity p, LocalDateTime now) {
        return p.getStatus() == PaymentStatus.PENDING && p.getCreatedAt() != null
                && p.getCreatedAt().isBefore(now.minus(ABANDONED_AFTER));
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
                         String bidStatus, UUID announcementId, String trackingNumber) {
        static final Links EMPTY = new Links(null, null, null, null, null, null, null, null);
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
                       COALESCE(b.status, lb.status) AS bid_status,
                       CAST(COALESCE(a.id, la.id) AS VARCHAR(36)) AS announcement_id,
                       COALESCE(b.tracking_number, lb.tracking_number) AS tracking_number
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
                    rs.getString("arrival_city"), rs.getString("bid_status"),
                    uuid(rs.getString("announcement_id")), rs.getString("tracking_number")));
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
