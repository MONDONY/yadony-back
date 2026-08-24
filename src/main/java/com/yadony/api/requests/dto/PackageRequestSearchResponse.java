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
    String currency,
    /** Moyens de paiement effectivement disponibles pour cette demande, du point de vue du
     *  voyageur qui la consulte : carte si ce voyageur a un compte Stripe Connect actif ET
     *  que la devise l'autorise, espèces toujours. Même règle que {@code AnnouncementResponse}
     *  (voir {@code AnnouncementPaymentRails}), calculée ici pour le voyageur appelant plutôt
     *  que pour un voyageur propriétaire fixe (une demande n'a pas de voyageur assigné). */
    Set<PaymentMethod> availablePaymentMethods,
    /**
     * Prix brut, commission Yadony incluse : ce que l'expéditeur paiera réellement, quand
     * {@code targetPriceEur} est le net que le voyageur toucherait. {@code null} si la demande
     * n'a pas de budget.
     *
     * <p>Ajouté par la décision produit A16. Cette surface ne servait que le net, si bien
     * qu'elle annonçait un tarif que personne ne paie, et différent de celui du détail de la
     * même demande. Le net reste servi : c'est l'information que cherche un voyageur, et il
     * ne révèle ici aucun taux privé (voir {@code PackageRequestSearchMapper#grossPriceEur}).
     */
    BigDecimal grossPriceEur
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
                info.matchScore(), info.tripId(), info.tripDepartureDate(), currency,
                availablePaymentMethods, grossPriceEur);
    }
}
