package com.yadony.api.admin.broadcast.rule;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.common.MatchingTextUtil;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Lecture des destinataires d'une règle compilée. Un seul {@code SELECT ... FROM users u} :
 * les prédicats de la règle sont des {@code EXISTS}, donc une ligne par compte, dans le compte
 * comme dans les pages. Tri stable {@code created_at, id} pour que les pages ne se chevauchent pas.
 */
@Repository
public class RuleAudienceRepository {

    private static final String PUSH_REACHABLE =
            "EXISTS (SELECT 1 FROM user_devices d WHERE d.user_id = u.id AND d.fcm_token IS NOT NULL)";

    /** Aucune coordonnée (ni téléphone, ni email) : le record est exposé tel quel à l'admin. */
    public record RecipientRow(UUID userId, String displayName, String country, String language,
                               boolean pushReachable, Instant signedUpAt) {}

    private final NamedParameterJdbcTemplate jdbc;

    public RuleAudienceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long count(CompiledRule rule) {
        return countWhere("SELECT COUNT(*) FROM users u WHERE " + rule.where(), rule.params());
    }

    /**
     * Ce compte précis fait-il partie de l'audience de la règle ? Même clause que le comptage
     * (compte actif compris), bornée à un identifiant : sert aux modals, qui jugent une personne à la fois.
     */
    public boolean matches(CompiledRule rule, UUID userId) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValues(rule.params().getValues());
        params.addValue("matchUserId", userId);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users u WHERE (" + rule.where() + ") AND u.id = :matchUserId", params, Long.class);
        return total != null && total > 0;
    }

    /** Destinataires joignables en push : au moins un appareil avec un jeton FCM. */
    public long countPushReachable(CompiledRule rule) {
        return countWhere("SELECT COUNT(*) FROM users u WHERE " + rule.where() + " AND " + PUSH_REACHABLE,
                rule.params());
    }

    public Page<UUID> pageIds(CompiledRule rule, Pageable pageable) {
        var content = jdbc.query("SELECT u.id FROM users u WHERE " + rule.where() + ORDER_AND_PAGE,
                paged(rule, pageable), (rs, i) -> rs.getObject(1, UUID.class));
        return new PageImpl<>(content, pageable, count(rule));
    }

    /** Clé de curseur : le couple de tri {@code (created_at, id)} du dernier compte lu. */
    public record IdCursor(UUID id, Instant createdAt) {}

    /**
     * Page par curseur (keyset) pour l'envoi : les comptes strictement après {@code after} dans
     * l'ordre {@code (created_at, id)}, {@code after} nul pour la première page. Contrairement à
     * {@code OFFSET}, un compte qui sort de la règle entre deux pages (dernière visite, par
     * exemple) ne décale pas les suivants : personne n'est sauté.
     */
    public List<IdCursor> idsAfter(CompiledRule rule, IdCursor after, int size) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValues(rule.params().getValues());
        params.addValue("size", size);
        String cursor = "";
        if (after != null) {
            cursor = " AND (u.created_at, u.id) > (:afterCreatedAt, :afterId)";
            params.addValue("afterCreatedAt", Timestamp.from(after.createdAt()));
            params.addValue("afterId", after.id());
        }
        return jdbc.query("SELECT u.id, u.created_at FROM users u WHERE (" + rule.where() + ")" + cursor
                        + " ORDER BY u.created_at, u.id LIMIT :size", params,
                (rs, i) -> new IdCursor(rs.getObject("id", UUID.class), rs.getTimestamp("created_at").toInstant()));
    }

    public Page<RecipientRow> pageRecipients(CompiledRule rule, Pageable pageable) {
        var content = jdbc.query(
                "SELECT u.id, u.first_name, u.last_name, u.username, u.country, u.preferred_language,"
                        + " u.created_at, " + PUSH_REACHABLE + " AS push_reachable"
                        + " FROM users u WHERE " + rule.where() + ORDER_AND_PAGE,
                paged(rule, pageable), (rs, i) -> new RecipientRow(
                        rs.getObject("id", UUID.class),
                        displayName(rs.getString("first_name"), rs.getString("last_name"), rs.getString("username")),
                        rs.getString("country"),
                        rs.getString("preferred_language"),
                        rs.getBoolean("push_reachable"),
                        rs.getTimestamp("created_at").toInstant()));
        return new PageImpl<>(content, pageable, count(rule));
    }

    private static final String ORDER_AND_PAGE = " ORDER BY u.created_at, u.id LIMIT :limit OFFSET :offset";

    private long countWhere(String sql, MapSqlParameterSource params) {
        Long total = jdbc.queryForObject(sql, params, Long.class);
        return total == null ? 0 : total;
    }

    private static MapSqlParameterSource paged(CompiledRule rule, Pageable pageable) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValues(rule.params().getValues());
        params.addValue("limit", pageable.getPageSize());
        params.addValue("offset", pageable.getOffset());
        return params;
    }

    /** Même règle que l'affichage admin ailleurs : on passe par {@link MatchingTextUtil#buildName}. */
    private static String displayName(String firstName, String lastName, String username) {
        UserEntity probe = new UserEntity();
        probe.setFirstName(firstName);
        probe.setLastName(lastName);
        probe.setUsername(username);
        return MatchingTextUtil.buildName(probe);
    }
}
