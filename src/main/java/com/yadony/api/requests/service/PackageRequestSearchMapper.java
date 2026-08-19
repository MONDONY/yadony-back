package com.yadony.api.requests.service;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.city.CityEntity;
import com.yadony.api.common.StorageService;
import com.yadony.api.config.PlatformSettingsService;
import com.yadony.api.requests.dto.PackageRequestPhotoResponse;
import com.yadony.api.requests.dto.PackageRequestSearchResponse;
import com.yadony.api.requests.entity.PackageRequestEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Stateless mapper: converts a {@link PackageRequestEntity} to a {@link PackageRequestSearchResponse}.
 * Extracted from {@link PackageRequestService#toSearchResponse} so that packages outside
 * {@code requests/} (e.g. {@code favorites/}) can map package-request entities without
 * importing the full {@code PackageRequestService} and creating a cross-package service cycle.
 *
 * <p>No business logic lives here — only field projection and helper calls.
 */
@Component
public class PackageRequestSearchMapper {

    private final UserRepository userRepository;
    private final com.yadony.api.city.CityRepository cityRepository;
    private final StorageService storageService;
    private final PackageRequestPhotoService photoService;
    private final PlatformSettingsService settings;

    public PackageRequestSearchMapper(UserRepository userRepository,
                                      com.yadony.api.city.CityRepository cityRepository,
                                      StorageService storageService,
                                      PackageRequestPhotoService photoService,
                                      PlatformSettingsService settings) {
        this.userRepository = userRepository;
        this.cityRepository = cityRepository;
        this.storageService = storageService;
        this.photoService = photoService;
        this.settings = settings;
    }

    /**
     * A request is "urgent" when its {@code desiredDate} falls within the next
     * {@code yadony.urgency.threshold-days} (bounds inclusive, today in UTC).
     */
    private boolean computeUrgent(java.time.LocalDate desiredDate) {
        if (desiredDate == null) return false;
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        int threshold = settings.urgencyThresholdDays();
        return !desiredDate.isBefore(today) && !desiredDate.isAfter(today.plusDays(threshold));
    }

    /**
     * Batch-aware mapping: reads sender users, city coordinates, and photos from pre-loaded maps
     * instead of issuing per-row queries.  Call this overload when mapping a list to avoid N+1.
     *
     * @param entity     the package-request entity (must not be null)
     * @param isFavorite whether the viewing user has favorited this request
     * @param userMap    pre-loaded map of senderId → UserEntity (absent = treated as null)
     * @param cityMap    pre-loaded map of lowercase city name → CityEntity (absent = no coords)
     * @param photoMap   pre-loaded map of packageRequestId → photo list (absent = empty list)
     */
    public PackageRequestSearchResponse toSearchResponse(PackageRequestEntity entity, boolean isFavorite,
                                                         Map<UUID, UserEntity> userMap,
                                                         Map<String, CityEntity> cityMap,
                                                         Map<UUID, List<PackageRequestPhotoResponse>> photoMap) {
        UserEntity sender = userMap.get(entity.getSenderId());
        String displayName = buildSenderDisplayName(sender);
        double averageRating = sender != null && sender.getAverageRating() != null
                ? sender.getAverageRating().doubleValue() : 0.0;
        int totalRatings = sender != null ? sender.getRatingCount() : 0;
        boolean kycVerified = sender != null && sender.getKycStatus() == KycStatus.VERIFIED;
        var senderProfile = new PackageRequestSearchResponse.SenderPublicProfile(
                entity.getSenderId(), displayName, averageRating, totalRatings, kycVerified,
                storageService.avatarUrl(sender != null ? sender.getAvatarUrl() : null)
        );
        var depCity = cityMap.get(entity.getDepartureCity() != null ? entity.getDepartureCity().toLowerCase() : "");
        var arrCity = cityMap.get(entity.getArrivalCity() != null ? entity.getArrivalCity().toLowerCase() : "");
        List<PackageRequestPhotoResponse> photos = photoMap.getOrDefault(entity.getId(), List.of());
        String photoUrl = photos.isEmpty() ? entity.getPhotoUrl() : photos.get(0).url();
        return new PackageRequestSearchResponse(
                entity.getId(), entity.getDepartureCity(), entity.getArrivalCity(),
                depCity != null ? depCity.getLatitude() : null,
                depCity != null ? depCity.getLongitude() : null,
                arrCity != null ? arrCity.getLatitude() : null,
                arrCity != null ? arrCity.getLongitude() : null,
                entity.getDesiredDate(),
                entity.getDateToleranceDays() != null ? entity.getDateToleranceDays().intValue() : 0,
                entity.getWeightKg(), entity.getParcelSize(), entity.getTransportMode(),
                entity.getContentCategory(),
                entity.getTargetPriceEur(), entity.isNegotiable(), photoUrl,
                entity.getPickupNeighborhood(), entity.getDeliveryNeighborhood(),
                senderProfile,
                entity.getAcceptedPaymentMethods(),
                photos,
                isFavorite,
                computeUrgent(entity.getDesiredDate()),
                null, null, null, entity.getCurrency()
        );
    }

    /**
     * Maps an entity to the search DTO.
     *
     * @param entity     the package-request entity (must not be null)
     * @param isFavorite whether the viewing user has favorited this request
     * @return the populated search response record
     */
    public PackageRequestSearchResponse toSearchResponse(PackageRequestEntity entity, boolean isFavorite) {
        UserEntity sender = userRepository.findById(entity.getSenderId()).orElse(null);
        String displayName = buildSenderDisplayName(sender);
        double averageRating = sender != null && sender.getAverageRating() != null
                ? sender.getAverageRating().doubleValue() : 0.0;
        int totalRatings = sender != null ? sender.getRatingCount() : 0;
        boolean kycVerified = sender != null && sender.getKycStatus() == KycStatus.VERIFIED;
        var senderProfile = new PackageRequestSearchResponse.SenderPublicProfile(
                entity.getSenderId(), displayName, averageRating, totalRatings, kycVerified,
                storageService.avatarUrl(sender != null ? sender.getAvatarUrl() : null)
        );
        var depCity = cityRepository.findFirstByNameIgnoreCase(entity.getDepartureCity()).orElse(null);
        var arrCity = cityRepository.findFirstByNameIgnoreCase(entity.getArrivalCity()).orElse(null);
        List<PackageRequestPhotoResponse> photos = photoService.activePhotos(entity.getId());
        String photoUrl = photos.isEmpty() ? entity.getPhotoUrl() : photos.get(0).url();
        return new PackageRequestSearchResponse(
                entity.getId(), entity.getDepartureCity(), entity.getArrivalCity(),
                depCity != null ? depCity.getLatitude() : null,
                depCity != null ? depCity.getLongitude() : null,
                arrCity != null ? arrCity.getLatitude() : null,
                arrCity != null ? arrCity.getLongitude() : null,
                entity.getDesiredDate(),
                entity.getDateToleranceDays() != null ? entity.getDateToleranceDays().intValue() : 0,
                entity.getWeightKg(), entity.getParcelSize(), entity.getTransportMode(),
                entity.getContentCategory(),
                entity.getTargetPriceEur(), entity.isNegotiable(), photoUrl,
                entity.getPickupNeighborhood(), entity.getDeliveryNeighborhood(),
                senderProfile,
                entity.getAcceptedPaymentMethods(),
                photos,
                isFavorite,
                computeUrgent(entity.getDesiredDate()),
                null, null, null, entity.getCurrency()
        );
    }

    /**
     * Batch convenience: maps a list of entities to DTOs using a single query per resource type
     * (users, cities, photos). All entities get {@code isFavorite=true} (used by FavoriteService).
     * For mixed isFavorite values, use {@link #toSearchResponse(PackageRequestEntity, boolean, Map, Map, Map)}.
     *
     * @param entities  the package-request entities to map
     * @param favIdSet  set of request IDs that the viewer has favorited
     */
    public List<PackageRequestSearchResponse> toSearchResponseList(List<PackageRequestEntity> entities,
                                                                    Set<UUID> favIdSet) {
        if (entities.isEmpty()) return List.of();

        List<UUID> senderIds = entities.stream().map(PackageRequestEntity::getSenderId).distinct().toList();
        Map<UUID, UserEntity> userMap = userRepository.findAllById(senderIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, u -> u, (a, b) -> a));

        Set<String> cityNames = new HashSet<>();
        for (PackageRequestEntity e : entities) {
            if (e.getDepartureCity() != null) cityNames.add(e.getDepartureCity());
            if (e.getArrivalCity() != null) cityNames.add(e.getArrivalCity());
        }
        Map<String, CityEntity> cityMap = cityRepository.findByNamesIgnoreCaseBatch(cityNames);

        List<UUID> requestIds = entities.stream().map(PackageRequestEntity::getId).toList();
        Map<UUID, List<PackageRequestPhotoResponse>> photoMap = photoService.activePhotosBatch(requestIds);

        List<PackageRequestSearchResponse> result = new ArrayList<>(entities.size());
        for (PackageRequestEntity e : entities) {
            result.add(toSearchResponse(e, favIdSet.contains(e.getId()), userMap, cityMap, photoMap));
        }
        return result;
    }

    /** Délègue à {@link UserEntity#publicDisplayName()} : repli sur le username du compte. */
    private String buildSenderDisplayName(UserEntity user) {
        if (user == null) return UserEntity.UNKNOWN_DISPLAY_NAME;
        return user.publicDisplayName();
    }
}
