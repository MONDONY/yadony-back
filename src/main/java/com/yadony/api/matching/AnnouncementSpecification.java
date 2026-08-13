package com.yadony.api.matching;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserBlockEntity;
import com.yadony.api.auth.UserEntity;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.UUID;

public class AnnouncementSpecification {

    public static Specification<AnnouncementEntity> hasStatus(AnnouncementStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<AnnouncementEntity> hasCurrency(String currency) {
        return (root, query, cb) -> cb.equal(root.get("currency"), currency);
    }

    public static Specification<AnnouncementEntity> hasDepartureCity(String city) {
        return (root, query, cb) -> cb.equal(cb.lower(root.get("departureCity")), city.toLowerCase());
    }

    public static Specification<AnnouncementEntity> hasArrivalCity(String city) {
        return (root, query, cb) -> cb.equal(cb.lower(root.get("arrivalCity")), city.toLowerCase());
    }

    public static Specification<AnnouncementEntity> departureDateFrom(LocalDate from) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("departureDate"), from);
    }

    public static Specification<AnnouncementEntity> departureDateTo(LocalDate to) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("departureDate"), to);
    }

    public static Specification<AnnouncementEntity> minAvailableKg(BigDecimal kg) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("availableKg"), kg);
    }

    public static Specification<AnnouncementEntity> maxAvailableKg(BigDecimal kg) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("availableKg"), kg);
    }

    public static Specification<AnnouncementEntity> maxPricePerKg(BigDecimal max) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("pricePerKg"), max);
    }

    /**
     * Filters announcements whose departure date falls on a Saturday (dow=6) or Sunday (dow=0).
     * Uses PostgreSQL date_part('dow', ...) function.
     */
    public static Specification<AnnouncementEntity> weekendOnly() {
        return (root, query, cb) -> {
            Expression<Double> dow = cb.function("date_part", Double.class,
                    cb.literal("dow"), root.get("departureDate"));
            return cb.or(cb.equal(dow, 0.0), cb.equal(dow, 6.0));
        };
    }

    /**
     * Filters announcements to travelers whose averageRating >= minRating.
     * Uses a subquery on users table to avoid cross-package service injection.
     */
    public static Specification<AnnouncementEntity> minRating(BigDecimal minRating) {
        return (root, query, cb) -> {
            Subquery<UUID> sq = query.subquery(UUID.class);
            Root<UserEntity> user = sq.from(UserEntity.class);
            sq.select(user.<UUID>get("id"))
              .where(cb.greaterThanOrEqualTo(user.get("averageRating"), minRating));
            return root.get("travelerId").in(sq);
        };
    }

    /**
     * Filters announcements to Kilo Pro travelers only.
     * Uses a subquery on users table to avoid cross-package service injection.
     */
    public static Specification<AnnouncementEntity> kiloProOnly() {
        return (root, query, cb) -> {
            Subquery<UUID> sq = query.subquery(UUID.class);
            Root<UserEntity> user = sq.from(UserEntity.class);
            sq.select(user.<UUID>get("id"))
              .where(cb.isTrue(user.get("kiloPro")));
            return root.get("travelerId").in(sq);
        };
    }

    public static Specification<AnnouncementEntity> hasTransportMode(TransportMode mode) {
        return (root, query, cb) -> cb.equal(root.get("transportMode"), mode);
    }

    /**
     * Filters to travelers who have completed KYC verification.
     * Uses a subquery on users table.
     */
    public static Specification<AnnouncementEntity> kycVerifiedOnly() {
        return (root, query, cb) -> {
            Subquery<UUID> sq = query.subquery(UUID.class);
            Root<UserEntity> user = sq.from(UserEntity.class);
            sq.select(user.<UUID>get("id"))
              .where(cb.equal(user.get("kycStatus"), KycStatus.VERIFIED));
            return root.get("travelerId").in(sq);
        };
    }

    /**
     * Filters announcements that accept a specific content type in their acceptedContentTypes list.
     */
    public static Specification<AnnouncementEntity> hasAcceptedContentType(String contentType) {
        return (root, query, cb) -> cb.isMember(contentType, root.get("acceptedContentTypes"));
    }

    public static Specification<AnnouncementEntity> idIn(Collection<UUID> ids) {
        return (root, query, cb) -> {
            if (ids == null || ids.isEmpty()) return cb.disjunction(); // always false → 0 rows
            return root.get("id").in(ids);
        };
    }

    public static Specification<AnnouncementEntity> hasPickupCoordinates() {
        return (root, query, cb) -> cb.and(
            cb.isNotNull(root.get("pickupLat")),
            cb.isNotNull(root.get("pickupLng"))
        );
    }

    /**
     * Excludes "dedicated trips" — trips tied to a specific package_request via
     * /negotiations/{id}/create-dedicated-trip. These must never appear in the
     * public search; they're visible only to the negotiating sender.
     */
    public static Specification<AnnouncementEntity> publicOnly() {
        return (root, query, cb) -> cb.isNull(root.get("linkedPackageRequestId"));
    }

    /**
     * Public search visibility: regular public trips PLUS dedicated trips that
     * have opened their surplus capacity to the public (surplusPublished &&
     * availableKg > 0). Dedicated trips without an open surplus stay hidden.
     */
    public static Specification<AnnouncementEntity> publicOrOpenSurplus() {
        return (root, query, cb) -> cb.or(
            cb.isNull(root.get("linkedPackageRequestId")),
            cb.and(
                cb.isTrue(root.get("surplusPublished")),
                cb.greaterThan(root.get("availableKg"), BigDecimal.ZERO)
            )
        );
    }

    /**
     * Excludes announcements whose traveler is in a block relation (either direction)
     * with the viewer (the searching user). Confidentialité v2.
     * Uses two subqueries on user_blocks to avoid cross-package service injection.
     * When {@code viewerId} is null (unauthenticated context), no filter is applied.
     */
    public static Specification<AnnouncementEntity> notBlockedBy(UUID viewerId) {
        return (root, query, cb) -> {
            if (viewerId == null) return cb.conjunction();

            // Travelers that the viewer has blocked.
            Subquery<UUID> blocked = query.subquery(UUID.class);
            Root<UserBlockEntity> b1 = blocked.from(UserBlockEntity.class);
            blocked.select(b1.<UUID>get("blockedId"))
                   .where(cb.equal(b1.get("blockerId"), viewerId));

            // Travelers that have blocked the viewer.
            Subquery<UUID> blockers = query.subquery(UUID.class);
            Root<UserBlockEntity> b2 = blockers.from(UserBlockEntity.class);
            blockers.select(b2.<UUID>get("blockerId"))
                    .where(cb.equal(b2.get("blockedId"), viewerId));

            return cb.and(
                    cb.not(root.get("travelerId").in(blocked)),
                    cb.not(root.get("travelerId").in(blockers)));
        };
    }
}
