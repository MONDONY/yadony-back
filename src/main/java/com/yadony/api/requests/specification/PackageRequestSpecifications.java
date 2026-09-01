package com.yadony.api.requests.specification;

import com.yadony.api.auth.UserBlockEntity;
import com.yadony.api.requests.entity.ParcelSize;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

public final class PackageRequestSpecifications {

    private PackageRequestSpecifications() {}

    public static Specification<PackageRequestEntity> openOnly() {
        return (root, query, cb) -> root.get("status").in(PackageRequestStatus.OPEN, PackageRequestStatus.NEGOTIATING);
    }

    public static Specification<PackageRequestEntity> corridor(String departure, String arrival) {
        return (root, query, cb) -> {
            if (departure == null && arrival == null) return cb.conjunction();
            var preds = cb.conjunction();
            if (departure != null) preds = cb.and(preds, cb.equal(cb.lower(root.get("departureCity")), departure.toLowerCase()));
            if (arrival != null) preds = cb.and(preds, cb.equal(cb.lower(root.get("arrivalCity")), arrival.toLowerCase()));
            return preds;
        };
    }

    public static Specification<PackageRequestEntity> dateRange(LocalDate from, LocalDate to) {
        return (root, query, cb) -> {
            if (from == null && to == null) return cb.conjunction();
            if (from != null && to != null) return cb.between(root.get("desiredDate"), from, to);
            if (from != null) return cb.greaterThanOrEqualTo(root.get("desiredDate"), from);
            return cb.lessThanOrEqualTo(root.get("desiredDate"), to);
        };
    }

    public static Specification<PackageRequestEntity> maxWeight(BigDecimal maxKg) {
        return (root, query, cb) -> maxKg == null ? cb.conjunction()
                : cb.lessThanOrEqualTo(root.get("weightKg"), maxKg);
    }

    public static Specification<PackageRequestEntity> parcelSize(ParcelSize size) {
        return (root, query, cb) -> size == null ? cb.conjunction()
                : cb.equal(root.get("parcelSize"), size);
    }

    /**
     * Restricts results to requests whose {@code desiredDate} falls within
     * {@code [today, today + thresholdDays]} (bounds inclusive, today in UTC).
     */
    public static Specification<PackageRequestEntity> urgent(int thresholdDays) {
        return (root, query, cb) -> {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            return cb.between(root.get("desiredDate"), today, today.plusDays(thresholdDays));
        };
    }

    /**
     * Restreint aux demandes dont l'id figure dans {@code ids}.
     * Une collection vide ne matche rien — jamais « tout » : sinon un voyageur
     * sans trajet actif verrait toutes les demandes de la plateforme.
     */
    public static Specification<PackageRequestEntity> idIn(java.util.Collection<java.util.UUID> ids) {
        return (root, query, cb) -> {
            if (ids == null || ids.isEmpty()) return cb.disjunction();
            return root.get("id").in(ids);
        };
    }

    /**
     * Exclut les demandes dont l'expéditeur est en relation de blocage avec le viewer,
     * dans un sens comme dans l'autre (masquage symétrique, confidentialité v2).
     *
     * <p>Deux sous-requêtes sur {@code user_blocks} plutôt qu'une injection de service
     * depuis {@code auth/} : le filtre doit s'évaluer en SQL, sinon la pagination porterait
     * sur un ensemble non filtré et les pages renverraient un nombre d'éléments variable.
     * Même approche que {@code AnnouncementSpecification.notBlockedBy} côté trajets.
     *
     * <p>Un {@code viewerId} nul (visiteur non authentifié) n'a aucune identité à confronter
     * aux blocages : aucun filtre appliqué.
     */
    public static Specification<PackageRequestEntity> notBlockedBy(UUID viewerId) {
        return (root, query, cb) -> {
            if (viewerId == null) return cb.conjunction();

            // Expéditeurs que le viewer a bloqués.
            Subquery<UUID> blocked = query.subquery(UUID.class);
            Root<UserBlockEntity> b1 = blocked.from(UserBlockEntity.class);
            blocked.select(b1.<UUID>get("blockedId"))
                   .where(cb.equal(b1.get("blockerId"), viewerId));

            // Expéditeurs qui ont bloqué le viewer.
            Subquery<UUID> blockers = query.subquery(UUID.class);
            Root<UserBlockEntity> b2 = blockers.from(UserBlockEntity.class);
            blockers.select(b2.<UUID>get("blockerId"))
                    .where(cb.equal(b2.get("blockedId"), viewerId));

            return cb.and(
                    cb.not(root.get("senderId").in(blocked)),
                    cb.not(root.get("senderId").in(blockers)));
        };
    }
}
