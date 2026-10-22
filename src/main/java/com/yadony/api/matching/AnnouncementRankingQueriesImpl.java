package com.yadony.api.matching;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.query.QueryUtils;

/** Implémentation du fragment {@link AnnouncementRankingQueries} (détectée par Spring Data). */
class AnnouncementRankingQueriesImpl implements AnnouncementRankingQueries {

    @PersistenceContext
    private EntityManager em;

    @Override
    public List<RankingRow> findRankingRows(Specification<AnnouncementEntity> spec, Pageable pageable) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<RankingRow> query = cb.createQuery(RankingRow.class);
        Root<AnnouncementEntity> root = query.from(AnnouncementEntity.class);
        // Même enchaînement que SimpleJpaRepository.getQuery : la spécification d'abord (elle
        // peut poser son propre tri, ex. bookableFirstThen), puis le tri du Pageable s'il existe.
        if (spec != null) {
            Predicate predicate = spec.toPredicate(root, query, cb);
            if (predicate != null) {
                query.where(predicate);
            }
        }
        query.select(cb.construct(RankingRow.class,
                root.get("id"), root.get("travelerId"), root.get("departureDate"), root.get("createdAt"),
                root.get("acceptedPaymentMethods"), root.get("status"), root.get("availableKg"),
                root.get("capacityUnit"), root.get("transportMode"), root.get("pricePerKgEur"),
                root.get("travelerIsGpPro")));
        if (pageable.getSort().isSorted()) {
            query.orderBy(QueryUtils.toOrders(pageable.getSort(), root, cb));
        }
        TypedQuery<RankingRow> typed = em.createQuery(query);
        if (pageable.isPaged()) {
            typed.setFirstResult((int) pageable.getOffset());
            typed.setMaxResults(pageable.getPageSize());
        }
        return typed.getResultList();
    }

    @Override
    public Map<UUID, List<String>> findAcceptedContentTypes(Collection<UUID> announcementIds) {
        Map<UUID, List<String>> out = new LinkedHashMap<>();
        if (announcementIds == null || announcementIds.isEmpty()) {
            return out;
        }
        List<Object[]> pairs = em.createQuery(
                        "SELECT a.id, t FROM AnnouncementEntity a JOIN a.acceptedContentTypes t WHERE a.id IN :ids",
                        Object[].class)
                .setParameter("ids", announcementIds)
                .getResultList();
        for (Object[] pair : pairs) {
            out.computeIfAbsent((UUID) pair[0], k -> new java.util.ArrayList<>()).add((String) pair[1]);
        }
        return out;
    }
}
