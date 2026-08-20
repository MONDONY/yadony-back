package com.yadony.api.matching;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.StorageService;
import com.yadony.api.config.PlatformSettingsService;
import com.yadony.api.matching.dto.AddressDto;
import com.yadony.api.matching.dto.AnnouncementPriceGridItemResponse;
import com.yadony.api.matching.dto.AnnouncementSearchResponse;
import com.yadony.api.matching.dto.TravelerProfileDto;
import com.yadony.api.payments.currency.AnnouncementPaymentRails;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Stateless mapper: converts an {@link AnnouncementEntity} to an {@link AnnouncementSearchResponse}.
 * Extracted from {@link AnnouncementService#toSearchResponse} so that packages outside
 * {@code matching/} (e.g. {@code favorites/}) can map announcement entities without
 * importing the full {@code AnnouncementService} and creating a cross-package service cycle.
 *
 * <p>No business logic lives here — only field projection and helper calls.
 */
@Component
public class AnnouncementSearchMapper {

    private final UserRepository userRepository;
    private final BidRepository bidRepository;
    private final PriceGridService priceGridService;
    private final StorageService storageService;
    private final PlatformSettingsService settings;

    public AnnouncementSearchMapper(UserRepository userRepository,
                                    BidRepository bidRepository,
                                    PriceGridService priceGridService,
                                    StorageService storageService,
                                    PlatformSettingsService settings) {
        this.userRepository = userRepository;
        this.bidRepository = bidRepository;
        this.priceGridService = priceGridService;
        this.storageService = storageService;
        this.settings = settings;
    }

    /**
     * A trip is "urgent" when its departure falls within the next
     * {@code yadony.urgency.threshold-days} (bounds inclusive, today in UTC).
     */
    private boolean computeUrgent(java.time.LocalDate departureDate) {
        if (departureDate == null) return false;
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        int threshold = settings.urgencyThresholdDays();
        return !departureDate.isBefore(today)
                && !departureDate.isAfter(today.plusDays(threshold));
    }

    /**
     * Batch-aware mapping: reads users, bid counts, and grid items from pre-loaded maps instead
     * of issuing per-row queries.  Call this overload when mapping a list to avoid N+1 queries.
     *
     * @param entity     the announcement entity (must not be null)
     * @param isFavorite whether the viewing user has favorited this trip
     * @param userMap    pre-loaded map of travelerId → UserEntity (absent = treated as null)
     * @param bidCountMap pre-loaded map of announcementId → visible bid count (absent = 0)
     * @param gridItemMap pre-loaded map of announcementId → grid items (absent = empty list)
     */
    public AnnouncementSearchResponse toSearchResponse(AnnouncementEntity entity, boolean isFavorite,
                                                       Map<UUID, UserEntity> userMap,
                                                       Map<UUID, Long> bidCountMap,
                                                       Map<UUID, List<AnnouncementPriceGridItemResponse>> gridItemMap) {
        UserEntity traveler = userMap.get(entity.getTravelerId());
        boolean kycVerified = traveler != null && traveler.getKycStatus() == KycStatus.VERIFIED;
        TravelerProfileDto profile = traveler != null
                ? new TravelerProfileDto(
                        traveler.getId(),
                        buildDisplayName(traveler),
                        traveler.getAverageRating() != null ? traveler.getAverageRating().doubleValue() : null,
                        traveler.getTotalTrips(),
                        traveler.isKiloPro(),
                        traveler.isProAccount(),
                        kycVerified,
                        storageService.avatarUrl(traveler.getAvatarUrl()),
                        !traveler.isContactKycOnly())
                : null;
        Set<com.yadony.api.payments.cash.PaymentMethod> availablePaymentMethods =
                AnnouncementPaymentRails.availableFor(entity.getCurrency(),
                        traveler != null && traveler.hasActiveStripeConnect());
        long bidsCount = bidCountMap.getOrDefault(entity.getId(), 0L);
        List<AnnouncementPriceGridItemResponse> gridItems =
                entity.getPricingMode() == PricingMode.MIXED
                        ? gridItemMap.getOrDefault(entity.getId(), List.of())
                        : List.of();
        return new AnnouncementSearchResponse(
                entity.getId(), entity.getTravelerId(),
                entity.getDepartureCity(), entity.getArrivalCity(),
                entity.getDepartureDate(),
                entity.getDepartureTime(), entity.getArrivalTime(),
                new AddressDto(entity.getPickupAddressLabel(),
                        entity.getPickupLat().doubleValue(), entity.getPickupLng().doubleValue()),
                new AddressDto(entity.getDeliveryAddressLabel(),
                        entity.getDeliveryLat().doubleValue(), entity.getDeliveryLng().doubleValue()),
                entity.getAvailableKg(), entity.getTotalKg(), entity.getPricePerKg(),
                pricePerKgDisplay(entity.getPricePerKg(), entity.getTravelerId()),
                entity.getTransportMode(),
                entity.getStatus().name(), bidsCount, profile,
                entity.getDescription(),
                entity.getAcceptedContentTypes(),
                entity.getRefusedTypes(),
                entity.getAcceptedPaymentMethods().stream().map(Enum::name).toList(),
                entity.getCapacityUnit(),
                entity.getCreatedAt(), entity.getUpdatedAt(),
                entity.getPricingMode(),
                gridItems,
                entity.getHandoverDeadline(),
                isFavorite,
                computeUrgent(entity.getDepartureDate()),
                entity.getCurrency(),
                entity.isNegotiable(),
                availablePaymentMethods,
                null, null
        );
    }

    /**
     * Maps an entity to the search DTO.
     *
     * @param entity     the announcement entity (must not be null)
     * @param isFavorite whether the viewing user has favorited this trip
     * @return the populated search response record
     */
    public AnnouncementSearchResponse toSearchResponse(AnnouncementEntity entity, boolean isFavorite) {
        UserEntity traveler = userRepository.findById(entity.getTravelerId()).orElse(null);
        boolean kycVerified = traveler != null && traveler.getKycStatus() == KycStatus.VERIFIED;
        TravelerProfileDto profile = traveler != null
                ? new TravelerProfileDto(
                        traveler.getId(),
                        buildDisplayName(traveler),
                        traveler.getAverageRating() != null ? traveler.getAverageRating().doubleValue() : null,
                        traveler.getTotalTrips(),
                        traveler.isKiloPro(),
                        traveler.isProAccount(),
                        kycVerified,
                        storageService.avatarUrl(traveler.getAvatarUrl()),
                        !traveler.isContactKycOnly())
                : null;
        Set<com.yadony.api.payments.cash.PaymentMethod> availablePaymentMethods =
                AnnouncementPaymentRails.availableFor(entity.getCurrency(),
                        traveler != null && traveler.hasActiveStripeConnect());
        long bidsCount = bidRepository.countVisibleByAnnouncementId(entity.getId());
        List<AnnouncementPriceGridItemResponse> gridItems =
                entity.getPricingMode() == PricingMode.MIXED
                        ? priceGridService.getAnnouncementGridItems(entity.getId(), entity.getTravelerId())
                        : List.of();
        return new AnnouncementSearchResponse(
                entity.getId(), entity.getTravelerId(),
                entity.getDepartureCity(), entity.getArrivalCity(),
                entity.getDepartureDate(),
                entity.getDepartureTime(), entity.getArrivalTime(),
                new AddressDto(entity.getPickupAddressLabel(),
                        entity.getPickupLat().doubleValue(), entity.getPickupLng().doubleValue()),
                new AddressDto(entity.getDeliveryAddressLabel(),
                        entity.getDeliveryLat().doubleValue(), entity.getDeliveryLng().doubleValue()),
                entity.getAvailableKg(), entity.getTotalKg(), entity.getPricePerKg(),
                pricePerKgDisplay(entity.getPricePerKg(), entity.getTravelerId()),
                entity.getTransportMode(),
                entity.getStatus().name(), bidsCount, profile,
                entity.getDescription(),
                entity.getAcceptedContentTypes(),
                entity.getRefusedTypes(),
                entity.getAcceptedPaymentMethods().stream().map(Enum::name).toList(),
                entity.getCapacityUnit(),
                entity.getCreatedAt(), entity.getUpdatedAt(),
                entity.getPricingMode(),
                gridItems,
                entity.getHandoverDeadline(),
                isFavorite,
                computeUrgent(entity.getDepartureDate()),
                entity.getCurrency(),
                entity.isNegotiable(),
                availablePaymentMethods,
                null, null
        );
    }

    /**
     * Batch convenience: maps a list of entities to DTOs using a single query per resource type
     * (users, bid counts, grid items). All entities must have {@code isFavorite=true} (used by
     * {@code FavoriteService}). For mixed isFavorite values, callers should use
     * {@link #toSearchResponse(AnnouncementEntity, boolean, Map, Map, Map)} with pre-built maps.
     *
     * @param entities   the announcement entities to map
     * @param favIdSet   set of announcement IDs that the viewer has favorited
     */
    public List<AnnouncementSearchResponse> toSearchResponseList(List<AnnouncementEntity> entities,
                                                                  Set<UUID> favIdSet) {
        if (entities.isEmpty()) return List.of();

        List<UUID> ids = entities.stream().map(AnnouncementEntity::getId).toList();
        List<UUID> travelerIds = entities.stream().map(AnnouncementEntity::getTravelerId).distinct().toList();

        Map<UUID, UserEntity> userMap = userRepository.findAllById(travelerIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, u -> u, (a, b) -> a));

        Map<UUID, Long> bidCountMap = bidRepository.countVisibleByAnnouncementIds(ids).stream()
                .collect(Collectors.toMap(
                        row -> (UUID) row[0],
                        row -> (Long) row[1],
                        (a, b) -> a));

        Map<UUID, List<AnnouncementPriceGridItemResponse>> gridItemMap = new HashMap<>();
        for (AnnouncementEntity e : entities) {
            if (e.getPricingMode() == PricingMode.MIXED) {
                gridItemMap.put(e.getId(), priceGridService.getAnnouncementGridItems(e.getId(), e.getTravelerId()));
            }
        }

        List<AnnouncementSearchResponse> result = new ArrayList<>(entities.size());
        for (AnnouncementEntity e : entities) {
            result.add(toSearchResponse(e, favIdSet.contains(e.getId()), userMap, bidCountMap, gridItemMap));
        }
        return result;
    }

    private java.math.BigDecimal pricePerKgDisplay(java.math.BigDecimal net, java.util.UUID travelerId) {
        return net == null ? null : priceGridService.displayPrice(net, travelerId);
    }

    /**
     * Délègue à {@link UserEntity#publicDisplayName()}, qui ne rend jamais {@code null}.
     *
     * <p>Voir {@code AnnouncementService.buildDisplayName} : le {@code null} d'origine faisait
     * afficher le numéro de téléphone du voyageur en guise de nom côté client.
     */
    private String buildDisplayName(UserEntity user) {
        return user.publicDisplayName();
    }
}
