package com.yadony.api.alerts;

import com.yadony.api.alerts.dto.AlertTripMatchDto;
import com.yadony.api.alerts.dto.CorridorAlertRequest;
import com.yadony.api.alerts.dto.CorridorAlertResponse;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.YadonyNotFoundException;
import com.yadony.api.common.MatchingTextUtil;
import com.yadony.api.config.ContentCategoryNormalizer;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.dto.MatchingRequestDto;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional
public class AlertService {

    private static final int MAX_ACTIVE_ALERTS = 20;
    private static final int MIN_ZONE_RADIUS_KM = 5;
    private static final int MAX_ZONE_RADIUS_KM = 300;

    private final CorridorAlertRepository alertRepository;
    private final UserRepository userRepository;
    private final PackageRequestRepository packageRequestRepository;
    private final AnnouncementRepository announcementRepository;

    public AlertService(CorridorAlertRepository alertRepository,
                        UserRepository userRepository,
                        PackageRequestRepository packageRequestRepository,
                        AnnouncementRepository announcementRepository) {
        this.alertRepository = alertRepository;
        this.userRepository = userRepository;
        this.packageRequestRepository = packageRequestRepository;
        this.announcementRepository = announcementRepository;
    }

    private UUID ownerId(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyNotFoundException("User not found"))
                .getId();
    }

    // Item 4: single findAllByOwnerId call for both cap check and duplicate check
    public CorridorAlertResponse create(String firebaseUid, CorridorAlertRequest req) {
        UserEntity owner = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyNotFoundException("User not found"));
        UUID oid = owner.getId();
        validateDirection(owner, req);

        List<CorridorAlertEntity> existing = alertRepository.findAllByOwnerId(oid);

        if (existing.size() >= MAX_ACTIVE_ALERTS) {
            throw new YadonyBusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "alert-limit-reached",
                    "Alert Limit Reached",
                    "Vous avez atteint la limite de " + MAX_ACTIVE_ALERTS + " alertes.");
        }

        // Normalisé à l'écriture (C2) : le check de doublon ci-dessous compare CETTE
        // liste à celles déjà persistées (normalisées, cf. javadoc de
        // ContentCategoryNormalizer) — la normaliser d'abord évite qu'un "Hi-fi" non
        // normalisé masque un doublon avec une alerte existante en "Téléphone & électronique".
        List<String> categories = new ArrayList<>(
                ContentCategoryNormalizer.normalizeList(
                        req.contentCategories() != null ? req.contentCategories() : List.of()));

        boolean duplicate = existing.stream()
                .anyMatch(e -> isSameFilters(e, req, categories));
        if (duplicate) {
            throw new YadonyBusinessException(
                    HttpStatus.CONFLICT,
                    "alert-duplicate",
                    "Duplicate Alert",
                    "Une alerte identique existe déjà pour ce corridor.");
        }

        CorridorAlertEntity entity = new CorridorAlertEntity();
        entity.setOwnerId(oid);
        entity.setDepartureCity(req.departureCity());
        entity.setDepartureCountryCode(req.departureCountryCode());
        entity.setArrivalCity(req.arrivalCity());
        entity.setArrivalCountryCode(req.arrivalCountryCode());
        entity.setDateFrom(req.dateFrom());
        entity.setDateTo(req.dateTo());
        entity.setMinWeightKg(req.minWeightKg());
        entity.setContentCategories(categories);
        entity.setActive(true);
        entity.setDirection(req.direction());
        applyZone(entity, req);

        CorridorAlertEntity saved = alertRepository.save(entity);
        return toResponse(saved, 0L);
    }

    /** Recopie la zone de remise (centre + rayon + label) du payload vers l'entité. */
    private void applyZone(CorridorAlertEntity entity, CorridorAlertRequest req) {
        entity.setCenterLat(req.centerLat());
        entity.setCenterLng(req.centerLng());
        entity.setRadiusKm(req.radiusKm());
        entity.setCenterLabel(req.centerLabel());
    }

    private boolean isSameFilters(CorridorAlertEntity e, CorridorAlertRequest req, List<String> categories) {
        return e.getDepartureCity().equalsIgnoreCase(req.departureCity())
                && e.getArrivalCity().equalsIgnoreCase(req.arrivalCity())
                && Objects.equals(e.getDateFrom(), req.dateFrom())
                && Objects.equals(e.getDateTo(), req.dateTo())
                && sameWeight(e.getMinWeightKg(), req.minWeightKg())
                && sameCategories(e.getContentCategories(), categories)
                && e.getDirection() == req.direction()
                && sameWeight(e.getCenterLat(), req.centerLat())
                && sameWeight(e.getCenterLng(), req.centerLng())
                && Objects.equals(e.getRadiusKm(), req.radiusKm());
    }

    private void validateDirection(UserEntity owner, CorridorAlertRequest req) {
        AlertDirection direction = req.direction();
        boolean roleOk = (direction == AlertDirection.SENDER_WANTS_TRIPS && owner.getRoles().contains(Role.SENDER))
                || (direction == AlertDirection.TRAVELER_WANTS_PACKAGES && owner.getRoles().contains(Role.TRAVELER));
        if (!roleOk) {
            throw new YadonyBusinessException(HttpStatus.FORBIDDEN,
                    "alert-direction-not-allowed", "Alert Direction Not Allowed",
                    "Votre rôle ne permet pas de créer ce type d'alerte.");
        }
        if (direction == AlertDirection.SENDER_WANTS_TRIPS
                && (req.minWeightKg() != null
                    || (req.contentCategories() != null && !req.contentCategories().isEmpty()))) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "alert-trip-filters-unsupported", "Trip Alert Filters Unsupported",
                    "Les filtres poids/catégories ne s'appliquent pas aux alertes trajet.");
        }
        validateZone(req.direction(), req.centerLat(), req.centerLng(), req.radiusKm());
    }

    /**
     * Zone de remise (centre + rayon) : optionnelle, réservée aux alertes trajet
     * (SENDER_WANTS_TRIPS). Tout-ou-rien (centre lat/lng + rayon), rayon borné
     * [{@value MIN_ZONE_RADIUS_KM}, {@value MAX_ZONE_RADIUS_KM}] km.
     */
    private void validateZone(AlertDirection direction, BigDecimal centerLat,
                              BigDecimal centerLng, Integer radiusKm) {
        boolean anyZone = centerLat != null || centerLng != null || radiusKm != null;
        if (!anyZone) {
            return;
        }
        if (direction != AlertDirection.SENDER_WANTS_TRIPS) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "alert-zone-not-allowed", "Pickup Zone Not Allowed",
                    "La zone de remise ne s'applique qu'aux alertes trajet.");
        }
        if (centerLat == null || centerLng == null || radiusKm == null) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "alert-zone-incomplete", "Pickup Zone Incomplete",
                    "La zone de remise exige un centre (lat/lng) et un rayon.");
        }
        if (radiusKm < MIN_ZONE_RADIUS_KM || radiusKm > MAX_ZONE_RADIUS_KM) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "alert-zone-radius-invalid", "Pickup Zone Radius Invalid",
                    "Le rayon doit être entre " + MIN_ZONE_RADIUS_KM + " et " + MAX_ZONE_RADIUS_KM + " km.");
        }
    }

    private boolean sameWeight(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.compareTo(b) == 0;
    }

    private boolean sameCategories(List<String> a, List<String> b) {
        List<String> normA = a != null ? a : List.of();
        List<String> normB = b != null ? b : List.of();
        return new HashSet<>(normA).equals(new HashSet<>(normB));
    }

    @Transactional(readOnly = true)
    public List<CorridorAlertResponse> list(String firebaseUid) {
        return list(firebaseUid, null);
    }

    @Transactional(readOnly = true)
    public List<CorridorAlertResponse> list(String firebaseUid, AlertDirection direction) {
        UUID oid = ownerId(firebaseUid);
        return alertRepository.findAllByOwnerId(oid).stream()
                .filter(a -> direction == null || a.getDirection() == direction)
                .map(a -> toResponse(a, countMatches(a)))
                .toList();
    }

    public CorridorAlertResponse update(String firebaseUid, UUID alertId,
                                        CorridorAlertRequest req, Boolean active) {
        UUID oid = ownerId(firebaseUid);
        CorridorAlertEntity entity = ownedAlert(oid, alertId);
        entity.setDepartureCity(req.departureCity());
        entity.setDepartureCountryCode(req.departureCountryCode());
        entity.setArrivalCity(req.arrivalCity());
        entity.setArrivalCountryCode(req.arrivalCountryCode());
        entity.setDateFrom(req.dateFrom());
        entity.setDateTo(req.dateTo());
        entity.setMinWeightKg(req.minWeightKg());
        entity.setContentCategories(ContentCategoryNormalizer.normalizeList(
                req.contentCategories() != null ? req.contentCategories() : List.of()));
        validateZone(entity.getDirection(), req.centerLat(), req.centerLng(), req.radiusKm());
        applyZone(entity, req);
        if (active != null) {
            entity.setActive(active);
        }
        CorridorAlertEntity saved = alertRepository.save(entity);
        return toResponse(saved, countMatches(saved));
    }

    public void delete(String firebaseUid, UUID alertId) {
        UUID oid = ownerId(firebaseUid);
        CorridorAlertEntity entity = ownedAlert(oid, alertId);
        entity.softDelete();
        alertRepository.save(entity);
    }

    @Transactional(readOnly = true)
    public List<MatchingRequestDto> getMatches(String firebaseUid, UUID alertId) {
        UUID oid = ownerId(firebaseUid);
        CorridorAlertEntity alert = ownedAlert(oid, alertId);
        return toMatchingDtos(findMatchingPackages(alert));
    }

    @Transactional(readOnly = true)
    public List<AlertTripMatchDto> getTripMatches(String firebaseUid, UUID alertId) {
        UUID oid = ownerId(firebaseUid);
        CorridorAlertEntity alert = ownedAlert(oid, alertId);
        return toTripDtos(findMatchingTrips(alert));
    }

    @Transactional(readOnly = true)
    public List<?> getMatchesForDirection(String firebaseUid, UUID alertId) {
        UUID oid = ownerId(firebaseUid);
        CorridorAlertEntity alert = ownedAlert(oid, alertId);
        return alert.getDirection() == AlertDirection.SENDER_WANTS_TRIPS
                ? toTripDtos(findMatchingTrips(alert))
                : toMatchingDtos(findMatchingPackages(alert));
    }

    private CorridorAlertEntity ownedAlert(UUID oid, UUID alertId) {
        CorridorAlertEntity entity = alertRepository.findById(alertId)
                .orElseThrow(() -> new YadonyNotFoundException("Alert not found"));
        if (!entity.getOwnerId().equals(oid)) {
            throw new YadonyNotFoundException("Alert not found");
        }
        return entity;
    }

    private long countMatches(CorridorAlertEntity alert) {
        return alert.getDirection() == AlertDirection.SENDER_WANTS_TRIPS
                ? findMatchingTrips(alert).size()
                : findMatchingPackages(alert).size();
    }

    public List<PackageRequestEntity> findRecentMatches(CorridorAlertEntity alert, java.time.LocalDateTime since) {
        return findMatchingPackages(alert).stream()
                .filter(p -> p.getCreatedAt() != null && p.getCreatedAt().isAfter(since))
                .toList();
    }

    public List<AnnouncementEntity> findRecentTripMatches(CorridorAlertEntity alert, java.time.LocalDateTime since) {
        return findMatchingTrips(alert).stream()
                .filter(a -> a.getCreatedAt() != null && a.getCreatedAt().isAfter(since))
                .toList();
    }

    private List<PackageRequestEntity> findMatchingPackages(CorridorAlertEntity alert) {
        return packageRequestRepository
                .findOpenByCorridor(alert.getDepartureCity(), alert.getArrivalCity())
                .stream()
                .filter(p -> fitsAlertDate(p.getDesiredDate(), alert))
                .filter(p -> fitsAlertWeight(p, alert))
                .filter(p -> fitsAlertCategory(p, alert))
                .toList();
    }

    private List<AnnouncementEntity> findMatchingTrips(CorridorAlertEntity alert) {
        // Zone de remise : on restreint en SQL aux trajets dont le pickup est
        // dans le cercle ; sinon corridor pur (ville→ville).
        List<AnnouncementEntity> candidates = alert.hasPickupZone()
                ? announcementRepository.findActiveByCorridorWithinPickupRadius(
                        alert.getDepartureCity(), alert.getArrivalCity(),
                        alert.getCenterLat().doubleValue(), alert.getCenterLng().doubleValue(),
                        alert.getRadiusKm())
                : announcementRepository.findActiveByCorridor(
                        alert.getDepartureCity(), alert.getArrivalCity());
        return candidates.stream()
                .filter(a -> fitsAlertDate(a.getDepartureDate(), alert))
                .toList();
    }

    private boolean fitsAlertDate(LocalDate date, CorridorAlertEntity alert) {
        if (alert.getDateFrom() != null && date.isBefore(alert.getDateFrom())) {
            return false;
        }
        return alert.getDateTo() == null || !date.isAfter(alert.getDateTo());
    }

    /**
     * Inverse de {@link #findMatchingTrips} : pour un trajet donné (qui vient
     * d'être créé), renvoie les alertes trajet (SENDER_WANTS_TRIPS) actives qui
     * matchent — corridor (ville→ville, insensible casse) + fenêtre de dates +
     * zone de remise (pickup dans le cercle si l'alerte en a une). Utilisé par le
     * matching temps réel (listener AnnouncementCreatedEvent).
     */
    @Transactional(readOnly = true)
    public List<CorridorAlertEntity> findSenderAlertsMatchingTrip(AnnouncementEntity trip) {
        if (trip.getStatus() != AnnouncementStatus.ACTIVE
                && trip.getStatus() != AnnouncementStatus.FULL) {
            return List.of();
        }
        return alertRepository
                .findAllByActiveTrueAndDirection(AlertDirection.SENDER_WANTS_TRIPS)
                .stream()
                .filter(a -> a.getDepartureCity().equalsIgnoreCase(trip.getDepartureCity())
                        && a.getArrivalCity().equalsIgnoreCase(trip.getArrivalCity()))
                .filter(a -> fitsAlertDate(trip.getDepartureDate(), a))
                .filter(a -> zoneContainsPickup(a, trip))
                .toList();
    }

    /** True si l'alerte n'a pas de zone, ou si le pickup du trajet est dans le cercle. */
    private boolean zoneContainsPickup(CorridorAlertEntity alert, AnnouncementEntity trip) {
        if (!alert.hasPickupZone()) {
            return true;
        }
        double distanceKm = haversineKm(
                alert.getCenterLat(), alert.getCenterLng(),
                trip.getPickupLat(), trip.getPickupLng());
        return distanceKm <= alert.getRadiusKm();
    }

    /** Distance haversine (km, rayon Terre 6371) entre deux points. */
    private static double haversineKm(BigDecimal lat1, BigDecimal lng1,
                                      BigDecimal lat2, BigDecimal lng2) {
        double radLat1 = Math.toRadians(lat1.doubleValue());
        double radLat2 = Math.toRadians(lat2.doubleValue());
        double dLat = Math.toRadians(lat2.doubleValue() - lat1.doubleValue());
        double dLng = Math.toRadians(lng2.doubleValue() - lng1.doubleValue());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(radLat1) * Math.cos(radLat2)
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private List<AlertTripMatchDto> toTripDtos(List<AnnouncementEntity> trips) {
        List<UUID> travelerIds = trips.stream()
                .map(AnnouncementEntity::getTravelerId).distinct().toList();
        Map<UUID, UserEntity> travelerMap = userRepository.findAllById(travelerIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, Function.identity()));
        List<AlertTripMatchDto> result = new ArrayList<>();
        for (AnnouncementEntity a : trips) {
            UserEntity traveler = travelerMap.get(a.getTravelerId());
            if (traveler == null) continue;
            double rating = traveler.getAverageRating() != null
                    ? traveler.getAverageRating().doubleValue() : 0.0;
            result.add(new AlertTripMatchDto(
                    a.getId(), a.getDepartureCity(), a.getArrivalCity(), a.getDepartureDate(),
                    traveler.getId(), MatchingTextUtil.buildPublicName(traveler),
                    MatchingTextUtil.buildInitials(traveler), rating,
                    a.getAvailableKg(), a.getPricePerKg(), a.getTransportMode(), null, a.getCurrency()));
        }
        return result;
    }

    // Item 6: renamed from fitsWeight
    private boolean fitsAlertWeight(PackageRequestEntity p, CorridorAlertEntity alert) {
        return alert.getMinWeightKg() == null
                || p.getWeightKg().compareTo(alert.getMinWeightKg()) >= 0;
    }

    // Item 6: renamed from fitsCategory
    // C1 : p.getContentCategory() est une liste jointe par virgule (multi-sélection,
    // cf. package_requests.content_category) — comparer la chaîne ENTIÈRE à chaque
    // catégorie voulue ne matchait jamais un colis multi-catégories. Aligné sur
    // BidContentRules.assertNotRefused : on splitte sur "," et on compare item par
    // item, en lower+trim (insensible à la casse comme le reste de la plateforme).
    private boolean fitsAlertCategory(PackageRequestEntity p, CorridorAlertEntity alert) {
        List<String> wanted = alert.getContentCategories();
        if (wanted == null || wanted.isEmpty()) {
            return true;
        }
        String packageCategories = p.getContentCategory();
        if (packageCategories == null || packageCategories.isBlank()) {
            return false;
        }
        List<String> wantedLower = wanted.stream()
                .map(c -> c.toLowerCase(Locale.ROOT).strip())
                .toList();
        for (String raw : packageCategories.split(",")) {
            String item = raw.toLowerCase(Locale.ROOT).strip();
            if (!item.isEmpty() && wantedLower.contains(item)) {
                return true;
            }
        }
        return false;
    }

    // Item 5: batch sender lookup — collect distinct sender IDs, single findAllById call
    private List<MatchingRequestDto> toMatchingDtos(List<PackageRequestEntity> packages) {
        List<UUID> senderIds = packages.stream()
                .map(PackageRequestEntity::getSenderId)
                .distinct()
                .toList();

        Map<UUID, UserEntity> senderMap = userRepository.findAllById(senderIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, Function.identity()));

        List<MatchingRequestDto> result = new ArrayList<>();
        for (PackageRequestEntity p : packages) {
            UserEntity sender = senderMap.get(p.getSenderId());
            if (sender == null) {
                // preserve existing "skip match if sender missing" behavior
                continue;
            }
            result.add(toMatchingDto(p, sender));
        }
        return result;
    }

    // Item 1: use MatchingTextUtil helpers (corridorLabel, buildName, buildInitials, truncate)
    private MatchingRequestDto toMatchingDto(PackageRequestEntity p, UserEntity sender) {
        String corridor = MatchingTextUtil.corridorLabel(p.getDepartureCity(), p.getArrivalCity());
        String senderName = MatchingTextUtil.buildPublicName(sender);
        String senderInitials = MatchingTextUtil.buildInitials(sender);
        double senderRating = sender.getAverageRating() != null
                ? sender.getAverageRating().doubleValue() : 0.0;
        return new MatchingRequestDto(
                p.getId().toString(),
                null,
                corridor,
                p.getDesiredDate().toString(),
                0.0,
                sender.getId() != null ? sender.getId().toString() : null,
                senderName,
                senderInitials,
                senderRating,
                sender.getTotalShipments(),
                p.getWeightKg().doubleValue(),
                p.getContentCategory(),
                0.0,
                p.getPhotoUrl(),
                MatchingTextUtil.truncate(p.getDescription(), 100),
                0,
                p.getCreatedAt() != null ? p.getCreatedAt().toString() : null,
                p.getCurrency());
    }

    private CorridorAlertResponse toResponse(CorridorAlertEntity e, long matchCount) {
        return new CorridorAlertResponse(
                e.getId(),
                e.getDepartureCity(),
                e.getArrivalCity(),
                e.getDepartureCountryCode(),
                e.getArrivalCountryCode(),
                e.getDateFrom(),
                e.getDateTo(),
                e.getMinWeightKg(),
                new ArrayList<>(e.getContentCategories()),
                e.getDirection(),
                e.isActive(),
                matchCount,
                e.getCreatedAt(),
                e.getCenterLat(),
                e.getCenterLng(),
                e.getRadiusKm(),
                e.getCenterLabel());
    }
}
