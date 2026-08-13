package com.yadony.api.requests.specification;

import com.yadony.api.requests.entity.ParcelSize;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import org.springframework.data.jpa.domain.Specification;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;

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

    public static Specification<PackageRequestEntity> hasCurrency(String currency) {
        return (root, query, cb) -> cb.equal(root.get("currency"), currency);
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
}
