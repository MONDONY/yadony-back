package com.yadony.api.toolkit;

import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class JpaToolsCompletionQueryRepository implements ToolsCompletionQueryRepository {

    private final EntityManager em;

    public JpaToolsCompletionQueryRepository(EntityManager em) {
        this.em = em;
    }

    @Override
    public long countAddresses(UUID userId) {
        return count("SELECT COUNT(*) FROM pickup_addresses WHERE user_id = :id AND deleted_at IS NULL", userId)
                + count("SELECT COUNT(*) FROM delivery_addresses WHERE user_id = :id AND deleted_at IS NULL", userId);
    }

    @Override
    public long countRecipients(UUID userId) {
        return count("SELECT COUNT(*) FROM recipients WHERE user_id = :id AND deleted_at IS NULL", userId);
    }

    /** Une alerte en pause reste configurée : pas de filtre sur {@code active}. */
    @Override
    public long countAlerts(UUID userId) {
        return count("SELECT COUNT(*) FROM corridor_alerts WHERE owner_id = :id AND deleted_at IS NULL", userId);
    }

    @Override
    public long countTripTemplates(UUID userId) {
        return count("SELECT COUNT(*) FROM trip_templates WHERE user_id = :id AND deleted_at IS NULL", userId);
    }

    @Override
    public long countPriceGridItems(UUID userId) {
        return count("SELECT COUNT(*) FROM traveler_price_grid_items WHERE traveler_id = :id AND deleted_at IS NULL", userId);
    }

    private long count(String sql, UUID userId) {
        Number n = (Number) em.createNativeQuery(sql).setParameter("id", userId).getSingleResult();
        return n == null ? 0L : n.longValue();
    }
}
