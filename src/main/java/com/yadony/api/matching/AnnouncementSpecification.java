package com.yadony.api.matching;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserBlockEntity;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.MobileMoneyPayoutStatus;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.currency.CurrencyPaymentRails;
import com.yadony.api.payments.currency.SupportedCurrency;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class AnnouncementSpecification {

    public static Specification<AnnouncementEntity> hasStatus(AnnouncementStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
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

    /**
     * Exclut les trajets dont la date limite de remise des colis est passée : on ne peut
     * plus y faire de demande ({@code BidService#assertCanBidOn} la refuse en 409).
     *
     * <p>{@code handoverDeadline} est une heure murale dans le fuseau du trajet, que la
     * requête compare à {@code nowUtc} faute de conversion par ligne portable (H2 en test).
     * Sur les corridors servis (UTC à UTC+2), l'écart laisse au pire un trajet visible
     * deux heures de trop ; la garde de création, elle, applique le fuseau exact.
     * Une annonce sans date limite (antérieure à V207) reste visible.
     */
    public static Specification<AnnouncementEntity> handoverDeadlineNotPassed(LocalDateTime nowUtc) {
        return (root, query, cb) -> cb.or(
                cb.isNull(root.get("handoverDeadline")),
                cb.greaterThan(root.get("handoverDeadline"), nowUtc));
    }

    public static Specification<AnnouncementEntity> minAvailableKg(BigDecimal kg) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("availableKg"), kg);
    }

    public static Specification<AnnouncementEntity> maxAvailableKg(BigDecimal kg) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("availableKg"), kg);
    }

    /**
     * Borne haute de prix comparée sur le pivot EUR ({@code price_per_kg_eur}), jamais
     * sur le brut : le fil mélange les devises, et « pricePerKg <= 10 » n'a aucun sens
     * entre une annonce à 8 EUR et une à 5000 XOF. L'appelant convertit la borne
     * (saisie dans la devise du lecteur) via {@code ExchangeRateService.toEurPivot}.
     * Pivot non null par construction (price_per_kg NOT NULL + CHECK > 0 depuis V3,
     * backfill V235) : aucune annonce n'échappe au filtre par absence de pivot.
     */
    public static Specification<AnnouncementEntity> maxPricePerKgEur(BigDecimal maxEur) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("pricePerKgEur"), maxEur);
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

    /**
     * Filtre « escales » (FLUTTER-GD), borne lue par {@link TripStops#searchBound}.
     *
     * <p>Décision produit sur les trajets sans information (tous ceux publiés avant
     * FLUTTER-GE, et tout trajet hors avion) :
     * <ul>
     *   <li>{@code maxStops = 0} (« Direct uniquement ») : exclus. L'expéditeur qui coche
     *       « direct » veut une garantie, un trajet non renseigné ne la donne pas ;</li>
     *   <li>{@code maxStops = 1} (« Max 1 escale ») : inclus, seul le « 2 escales ou plus »
     *       déclaré est écarté ;</li>
     *   <li>aucune borne (« Peu importe ») : pas de filtre, tout est inclus.</li>
     * </ul>
     */
    public static Specification<AnnouncementEntity> maxStops(int maxStops) {
        return (root, query, cb) -> {
            Expression<Short> stops = root.get("stopsCount");
            Short bound = (short) maxStops;
            if (maxStops == 0) {
                return cb.equal(stops, bound);
            }
            return cb.or(cb.isNull(stops), cb.lessThanOrEqualTo(stops, bound));
        };
    }

    /**
     * Filtre « moyens de paiement » (FLUTTER-G0) : garde les trajets qui offrent au moins un
     * des moyens demandés. Pendant SQL exact de {@code AnnouncementPaymentRails#offerable},
     * la règle qui calcule {@code availablePaymentMethods} renvoyé par la recherche :
     * <pre>
     * espèces      = acceptées par le trajet
     * carte        = acceptée ET devise qui l'autorise ET voyageur Stripe Connect actif
     * mobile money = accepté ET devise qui l'autorise ET compte de versement actif
     * </pre>
     * Évalué dans la requête, avant la pagination : la page et {@code totalElements} ne
     * comptent que les trajets retenus. Toute évolution d'{@code offerable} doit être
     * reportée ici ({@code AnnouncementPaymentMethodFilterIntegrationTest} vérifie l'accord).
     */
    public static Specification<AnnouncementEntity> offersAnyPaymentMethod(Collection<PaymentMethod> wanted) {
        return (root, query, cb) -> {
            List<Predicate> any = new ArrayList<>();
            for (PaymentMethod method : wanted) {
                Predicate offered = switch (method) {
                    case CASH -> accepts(root, cb, PaymentMethod.CASH);
                    case STRIPE -> cb.and(
                            accepts(root, cb, PaymentMethod.STRIPE),
                            currencyAllows(root, cb, PaymentMethod.STRIPE),
                            travelerMatches(root, query, cb, "stripeAccountStatus",
                                    StripeAccountStatus.ONBOARDING_COMPLETE));
                    case MOBILE_MONEY -> cb.and(
                            accepts(root, cb, PaymentMethod.MOBILE_MONEY),
                            currencyAllows(root, cb, PaymentMethod.MOBILE_MONEY),
                            travelerMatches(root, query, cb, "mobileMoneyStatus",
                                    MobileMoneyPayoutStatus.ACTIVE));
                    default -> null; // moyens retirés : jamais offerts
                };
                if (offered != null) {
                    any.add(offered);
                }
            }
            return any.isEmpty() ? cb.disjunction() : cb.or(any.toArray(Predicate[]::new));
        };
    }

    /** La liste textuelle « {STRIPE,CASH} » (PaymentMethodSetConverter) contient le moyen. */
    private static Predicate accepts(Root<AnnouncementEntity> root, CriteriaBuilder cb, PaymentMethod method) {
        return cb.like(root.get("acceptedPaymentMethods").as(String.class), "%" + method.name() + "%");
    }

    /**
     * La devise du trajet autorise le rail ({@link CurrencyPaymentRails}). Une devise inconnue
     * retombe sur l'EUR ({@link SupportedCurrency#fromCodeOrDefault}) : pour le rail carte,
     * on exclut donc les devises qui l'interdisent plutôt que de lister celles qui l'autorisent.
     */
    private static Predicate currencyAllows(Root<AnnouncementEntity> root, CriteriaBuilder cb, PaymentMethod method) {
        Expression<String> currency = cb.upper(root.get("currency"));
        boolean defaultAllows = CurrencyPaymentRails.allows(SupportedCurrency.EUR, method);
        List<String> codes = Arrays.stream(SupportedCurrency.values())
                .filter(c -> CurrencyPaymentRails.allows(c, method) != defaultAllows)
                .map(c -> c.code().toUpperCase(Locale.ROOT))
                .toList();
        if (codes.isEmpty()) {
            return defaultAllows ? cb.conjunction() : cb.disjunction();
        }
        return defaultAllows ? cb.not(currency.in(codes)) : currency.in(codes);
    }

    private static Predicate travelerMatches(Root<AnnouncementEntity> root,
                                             jakarta.persistence.criteria.CriteriaQuery<?> query,
                                             CriteriaBuilder cb, String attribute, Object value) {
        Subquery<UUID> sq = query.subquery(UUID.class);
        Root<UserEntity> user = sq.from(UserEntity.class);
        sq.select(user.<UUID>get("id")).where(cb.equal(user.get(attribute), value));
        return root.get("travelerId").in(sq);
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
     *
     * <p>Pendant SQL EXACT de {@link AnnouncementEntity#isPubliclyVisible()} (utilisé
     * en mémoire par les alertes corridor et la page publique) : toute modification
     * de l'un doit être reportée dans l'autre ({@code AnnouncementSurplusSpecificationTest}
     * vérifie l'accord des deux).
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
