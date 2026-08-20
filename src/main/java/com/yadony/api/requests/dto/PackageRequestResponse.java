package com.yadony.api.requests.dto;

import com.yadony.api.matching.TransportMode;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.requests.entity.ParcelSize;
import com.yadony.api.requests.entity.PackageRequestStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record PackageRequestResponse(
    UUID id, UUID senderId,
    String departureCity, String arrivalCity,
    LocalDate desiredDate, int dateToleranceDays,
    BigDecimal weightKg, ParcelSize parcelSize,
    TransportMode transportMode,
    String contentCategory,
    String description, BigDecimal targetPriceEur, String photoUrl,
    String pickupNeighborhood, String deliveryNeighborhood,
    PackageRequestStatus status,
    LocalDateTime createdAt,
    // Modèle B : champs négociation
    boolean negotiable,
    Set<PaymentMethod> acceptedPaymentMethods,
    /** Prix brut (commission incluse) affiché à l'expéditeur — null si pas de budget. */
    BigDecimal grossPriceEur,
    /** Photos colis présignées (max 4, ordonnées). photoUrl = 1ère pour rétro-compat. */
    List<PackageRequestPhotoResponse> photos,
    /** Thread de négociation ACTIF du voyageur appelant sur cette demande (null s'il n'en a pas). */
    UUID viewerThreadId,
    /** Statut de ce thread (OPEN, AWAITING_TRIP, AWAITING_PAYMENT, ACCEPTED) — null sinon. */
    String viewerThreadStatus,
    /** Code promo saisi à la publication (brut, null si aucun) — pré-remplit l'édition. */
    String promoCode,
    String currency,
    /** Moyens de paiement effectivement disponibles pour cette demande, du point de vue du
     *  voyageur qui la consulte : carte si ce voyageur a un compte Stripe Connect actif ET
     *  que la devise l'autorise, espèces toujours. Même règle que {@code AnnouncementResponse}
     *  (voir {@code AnnouncementPaymentRails}), calculée ici pour le voyageur appelant plutôt
     *  que pour un voyageur propriétaire fixe (une demande n'a pas de voyageur assigné). */
    Set<PaymentMethod> availablePaymentMethods
) {
    /** Constructeur de compatibilité (sans promoCode/currency/availablePaymentMethods) — évite de retoucher tous les tests. */
    public PackageRequestResponse(
            UUID id, UUID senderId,
            String departureCity, String arrivalCity,
            LocalDate desiredDate, int dateToleranceDays,
            BigDecimal weightKg, ParcelSize parcelSize,
            TransportMode transportMode,
            String contentCategory,
            String description, BigDecimal targetPriceEur, String photoUrl,
            String pickupNeighborhood, String deliveryNeighborhood,
            PackageRequestStatus status,
            LocalDateTime createdAt,
            boolean negotiable,
            Set<PaymentMethod> acceptedPaymentMethods,
            BigDecimal grossPriceEur,
            List<PackageRequestPhotoResponse> photos,
            UUID viewerThreadId,
            String viewerThreadStatus) {
        this(id, senderId, departureCity, arrivalCity, desiredDate, dateToleranceDays, weightKg, parcelSize,
            transportMode, contentCategory, description, targetPriceEur, photoUrl, pickupNeighborhood,
            deliveryNeighborhood, status, createdAt, negotiable, acceptedPaymentMethods, grossPriceEur, photos,
            viewerThreadId, viewerThreadStatus, null, "EUR", Set.of(PaymentMethod.CASH));
    }
}
