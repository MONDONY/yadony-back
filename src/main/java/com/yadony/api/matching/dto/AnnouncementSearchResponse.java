package com.yadony.api.matching.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.yadony.api.payments.cash.PaymentMethod;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record AnnouncementSearchResponse(
        UUID id,
        UUID travelerId,
        String departureCity,
        String arrivalCity,
        LocalDate departureDate,
        @JsonFormat(pattern = "HH:mm") LocalTime departureTime,
        @JsonFormat(pattern = "HH:mm") LocalTime arrivalTime,
        AddressDto pickupAddress,
        AddressDto deliveryAddress,
        BigDecimal availableKg,
        BigDecimal totalKg,
        BigDecimal pricePerKg,
        BigDecimal pricePerKgDisplay,
        com.yadony.api.matching.TransportMode transportMode,
        String status,
        long bidsCount,
        TravelerProfileDto traveler,
        String description,
        List<String> acceptedContentTypes,
        List<String> refusedTypes,
        List<String> acceptedPaymentMethods,
        com.yadony.api.matching.CapacityUnit capacityUnit,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        com.yadony.api.matching.PricingMode pricingMode,
        List<AnnouncementPriceGridItemResponse> priceGridItems,
        LocalDateTime handoverDeadline,
        boolean isFavorite,
        boolean urgent,
        String currency,
        /** Le voyageur accepte les propositions de prix : pilote le badge « prix
         *  négociable » du feed et l'entrée vers le fil de négociation. */
        boolean negotiable,
        /** Moyens de paiement effectivement disponibles pour ce trajet : carte si le voyageur
         *  a un compte Stripe Connect actif ET que la devise l'autorise, espèces toujours.
         *  Calculé côté serveur (voir AnnouncementPaymentRails) pour que le front n'ait pas à
         *  rejouer la règle. */
        Set<PaymentMethod> availablePaymentMethods,
        /** Équivalent de {@code pricePerKg} converti dans la devise du lecteur (fil devenu
         *  multidevise, Tâche 10). {@code null} quand {@code pricePerKg} est lui-même null
         *  (mode MIXED sans prix au kilo) ou que le contexte de mapping n'a pas de lecteur
         *  identifié (ex. favoris). Voir {@link #withConvertedPrice}. */
        BigDecimal convertedPricePerKg,
        /** Devise cible de {@code convertedPricePerKg} : celle du lecteur. Même valeur que
         *  {@code currency} quand l'annonce est déjà dans la devise du lecteur. */
        String convertedCurrency
) {
    /**
     * Copie enrichie du prix converti dans la devise du lecteur. Utilisé par
     * {@code AnnouncementService.searchAnnouncements} une fois le prix original mappé, pour ne
     * pas faire porter la conversion (dépendante du lecteur) au mapper partagé avec les favoris.
     */
    public AnnouncementSearchResponse withConvertedPrice(BigDecimal convertedPricePerKg, String convertedCurrency) {
        return new AnnouncementSearchResponse(
                id, travelerId, departureCity, arrivalCity, departureDate, departureTime, arrivalTime,
                pickupAddress, deliveryAddress, availableKg, totalKg, pricePerKg, pricePerKgDisplay,
                transportMode, status, bidsCount, traveler, description, acceptedContentTypes, refusedTypes,
                acceptedPaymentMethods, capacityUnit, createdAt, updatedAt, pricingMode, priceGridItems,
                handoverDeadline, isFavorite, urgent, currency, negotiable, availablePaymentMethods,
                convertedPricePerKg, convertedCurrency);
    }
}
