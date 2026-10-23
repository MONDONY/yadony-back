package com.yadony.api.admin;

import com.yadony.api.admin.dto.AdminMobileMoneyCountsResponse;
import com.yadony.api.admin.dto.AdminMobileMoneyResponse;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.MatchingTextUtil;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Lecture des listes finance de l'admin en SQL natif ({@link AdminFinanceSql}) : filtres, vues, nombres et
 * recherche écrits une fois, partagés par la page et les nombres. Lecture seule.
 */
@Component
public class AdminFinanceListings {

    private final NamedParameterJdbcTemplate jdbc;
    private final PawapayOperationRepository operationRepository;
    private final UserRepository userRepository;

    public AdminFinanceListings(NamedParameterJdbcTemplate jdbc, PawapayOperationRepository operationRepository,
                                UserRepository userRepository) {
        this.jdbc = jdbc;
        this.operationRepository = operationRepository;
        this.userRepository = userRepository;
    }

    // ── Mobile money ────────────────────────────────────────────────────────────

    /** Une ligne de page : l'opération et ce que la requête a résolu autour d'elle. */
    private record MobileMoneyRow(UUID operationId, UUID personId, String role, UUID bidId, String trackingNumber,
                                  String departureCity, String arrivalCity, UUID walletRefundRequestId,
                                  boolean retryable) {
    }

    /**
     * Opérations pawaPay, plus récentes d'abord : vue (absente = {@code ALL}), type, fournisseur et recherche
     * {@code q} (jamais sur le téléphone). Personne, colis, trajet et relance possible résolus en SQL ; le
     * numéro reste masqué.
     */
    @Transactional(readOnly = true)
    public Page<AdminMobileMoneyResponse> mobileMoney(AdminMobileMoneyView view, PawapayOperationKind kind,
                                                      String provider, String q, Pageable pageable) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("view", (view != null ? view : AdminMobileMoneyView.ALL).name());
        String where = mobileMoneyWhere(kind, provider, q, params) + " AND " + AdminFinanceSql.MM_VIEW_PREDICATE;
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM" + AdminFinanceSql.MM_FROM + where, params, Long.class);
        if (total == null || total == 0) {
            return new PageImpl<>(List.of(), pageable, 0);
        }
        params.addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        List<MobileMoneyRow> rows = jdbc.query(AdminFinanceSql.MM_SELECT + "FROM" + AdminFinanceSql.MM_FROM + where
                        + AdminFinanceSql.MM_ORDER_BY + " LIMIT :limit OFFSET :offset", params,
                (rs, i) -> new MobileMoneyRow(
                        uuid(rs.getString("operationId")),
                        uuid(rs.getString("personId")),
                        rs.getString("role"),
                        uuid(rs.getString("bidId")),
                        rs.getString("trackingNumber"),
                        rs.getString("departureCity"),
                        rs.getString("arrivalCity"),
                        uuid(rs.getString("walletRefundRequestId")),
                        rs.getInt("retryable") == 1));
        Map<UUID, PawapayOperationEntity> operations = operationRepository
                .findAllById(rows.stream().map(MobileMoneyRow::operationId).toList()).stream()
                .collect(Collectors.toMap(PawapayOperationEntity::getId, Function.identity()));
        List<UUID> personIds = rows.stream().map(MobileMoneyRow::personId).filter(Objects::nonNull).distinct().toList();
        // Comptes supprimés compris : l'opération reste lisible avec le nom de qui elle a servi.
        Map<UUID, String> names = personIds.isEmpty() ? Map.of()
                : userRepository.findAllByIdIncludingDeleted(personIds).stream()
                        .collect(Collectors.toMap(UserEntity::getId, MatchingTextUtil::buildName, (a, b) -> a));
        List<AdminMobileMoneyResponse> content = rows.stream()
                .filter(r -> operations.containsKey(r.operationId()))
                .map(r -> AdminMobileMoneyResponse.from(operations.get(r.operationId()), contextOf(r, names)))
                .toList();
        return new PageImpl<>(content, pageable, total);
    }

    /** Nombres par vue, sur les mêmes {@code kind}, {@code provider} et {@code q} que la liste. */
    @Transactional(readOnly = true)
    public AdminMobileMoneyCountsResponse mobileMoneyCounts(PawapayOperationKind kind, String provider, String q) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = mobileMoneyWhere(kind, provider, q, params);
        return jdbc.queryForObject(AdminFinanceSql.MM_COUNTS_SELECT + "FROM" + AdminFinanceSql.MM_FROM + where, params,
                (rs, i) -> new AdminMobileMoneyCountsResponse(rs.getLong("failed"), rs.getLong("open"),
                        rs.getLong("done"), rs.getLong("all"), rs.getLong("attention")));
    }

    private static String mobileMoneyWhere(PawapayOperationKind kind, String provider, String q,
                                           MapSqlParameterSource params) {
        params.addValue("kind", kind != null ? kind.name() : null);
        params.addValue("provider", provider == null || provider.isBlank() ? null : provider.strip());
        StringBuilder where = new StringBuilder(" WHERE ").append(AdminFinanceSql.MM_FILTERS);
        String query = q == null ? null : q.strip();
        if (query != null && !query.isEmpty()) {
            where.append(" AND ").append(AdminFinanceSql.MM_SEARCH_PREDICATE);
            addSearchTerms(params, query);
        }
        return where.toString();
    }

    /**
     * Lie {@code :t1} à {@code :t4} (jetons repliés, le {@code @} d'un pseudo retiré ; une saisie sans jeton
     * utilisable ne trouve rien par le texte, voir {@link AdminBidQuery#NO_MATCH_TOKEN}), {@code :idTerm} (UUID
     * en minuscules, ou null) et {@code :refTerm} (saisie brute).
     */
    private static void addSearchTerms(MapSqlParameterSource params, String query) {
        List<String> tokens = AdminSearchTerms.tokens(query.replace("@", ""), AdminBidQuery.MAX_TOKENS);
        if (tokens.isEmpty()) {
            tokens = List.of(AdminBidQuery.NO_MATCH_TOKEN);
        }
        for (int i = 0; i < AdminBidQuery.MAX_TOKENS; i++) {
            params.addValue("t" + (i + 1), i < tokens.size() ? tokens.get(i) : null);
        }
        UUID id = AdminSearchTerms.uuid(query);
        params.addValue("idTerm", id == null ? null : id.toString());
        params.addValue("refTerm", query);
    }

    private static AdminMobileMoneyResponse.Context contextOf(MobileMoneyRow r, Map<UUID, String> names) {
        boolean known = r.personId() != null;
        String corridor = r.departureCity() != null && r.arrivalCity() != null
                ? MatchingTextUtil.corridorLabel(r.departureCity(), r.arrivalCity()) : null;
        return new AdminMobileMoneyResponse.Context(r.personId(), known ? names.get(r.personId()) : null,
                known ? r.role() : null, r.bidId(), r.trackingNumber(), corridor, r.walletRefundRequestId(),
                r.retryable());
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}
