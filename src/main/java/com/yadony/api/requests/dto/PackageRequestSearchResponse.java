package com.yadony.api.requests.dto;

import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.requests.entity.ParcelSize;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record PackageRequestSearchResponse(
    UUID id,
    String departureCity, String arrivalCity,
    BigDecimal departureLat, BigDecimal departureLng,
    BigDecimal arrivalLat, BigDecimal arrivalLng,
    LocalDate desiredDate, int dateToleranceDays,
    BigDecimal weightKg, ParcelSize parcelSize,
    TransportMode transportMode,
    String contentCategory,
    BigDecimal targetPriceEur, boolean negotiable, String photoUrl,
    String pickupNeighborhood, String deliveryNeighborhood,
    SenderPublicProfile sender,
    Set<PaymentMethod> acceptedPaymentMethods,
    /** Photos colis présignées (max 4, ordonnées). photoUrl = 1ère pour rétro-compat. */
    List<PackageRequestPhotoResponse> photos,
    /** True si le voyageur authentifié a mis cette demande en favori. False pour les appelants anonymes ou non-voyageurs. */
    boolean isFavorite,
    /** True si desiredDate ∈ [today, today + yadony.urgency.threshold-days] (bornes incluses, today en UTC). */
    boolean urgent,
    /** Score de compatibilité 0–100 avec le meilleur trajet actif du voyageur. Null hors filtre matchingMyTrips. */
    Integer matchScore,
    /** Trajet du voyageur retenu pour ce match. Null hors filtre matchingMyTrips. */
    UUID matchedTripId,
    /** Date de départ du trajet retenu. Null hors filtre matchingMyTrips. */
    LocalDate matchedTripDepartureDate,
    String currency
) {
    public record SenderPublicProfile(UUID id, String displayName, double averageRating, int totalRatings, boolean kycVerified, String avatarUrl) {}

    /** Copie enrichie des informations de match. Utilisé uniquement quand matchingMyTrips est actif. */
    public PackageRequestSearchResponse withMatch(com.yadony.api.matching.MatchingService.MatchInfo info) {
        return new PackageRequestSearchResponse(
                id, departureCity, arrivalCity,
                departureLat, departureLng, arrivalLat, arrivalLng,
                desiredDate, dateToleranceDays,
                weightKg, parcelSize, transportMode, contentCategory,
                targetPriceEur, negotiable, photoUrl,
                pickupNeighborhood, deliveryNeighborhood,
                sender, acceptedPaymentMethods, photos, isFavorite, urgent,
                info.matchScore(), info.tripId(), info.tripDepartureDate(), currency);
    }
}
