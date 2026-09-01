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

public record AnnouncementDetailResponse(
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
        long confirmedParcelCount,
        TravelerProfileDto traveler,
        String description,
        List<String> acceptedContentTypes,
        List<String> refusedTypes,
        List<String> acceptedPaymentMethods,
        com.yadony.api.matching.CapacityUnit capacityUnit,
        boolean cashAccepted,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        com.yadony.api.matching.PricingMode pricingMode,
        List<AnnouncementPriceGridItemResponse> priceGridItems,
        BigDecimal reservedKg,
        boolean surplusEligible,
        boolean surplusPublished,
        LocalDateTime handoverDeadline,
        String currency,
        String arrivalInstructions,
        /** Le voyageur accepte les propositions de prix : c'est ce drapeau qui autorise
         *  l'expéditeur à ouvrir un fil via {@code POST /announcements/{id}/bids/negotiation}
         *  (sinon 422 {@code announcement-not-negotiable}). */
        boolean negotiable,
        /** Moyens de paiement effectivement disponibles pour ce trajet : carte si le voyageur
         *  a un compte Stripe Connect actif ET que la devise l'autorise, espèces toujours.
         *  Calculé côté serveur (voir AnnouncementPaymentRails) pour que le front n'ait pas à
         *  rejouer la règle. */
        Set<PaymentMethod> availablePaymentMethods,
        /**
         * Équivalent ESTIMÉ de {@code pricePerKg} (net voyageur) dans la devise active
         * du lecteur, au taux courant. {@code null} pour un invité (le net lui est déjà
         * masqué), sans prix au kilo, ou quand le lecteur lit déjà dans la devise de
         * l'annonce. Repère de lecture — le montant échangé reste dans la devise de
         * l'annonce.
         */
        BigDecimal convertedPricePerKg,
        /** Équivalent ESTIMÉ de {@code pricePerKgDisplay} (brut expéditeur), servi à tous. */
        BigDecimal pricePerKgDisplayConverted,
        /** Devise cible des équivalents convertis : celle du lecteur. */
        String convertedCurrency
) {
    /** Copie enrichie des équivalents convertis — même pattern que le fil de recherche. */
    public AnnouncementDetailResponse withConvertedPrices(BigDecimal convertedPricePerKg,
                                                          BigDecimal pricePerKgDisplayConverted,
                                                          String convertedCurrency,
                                                          List<AnnouncementPriceGridItemResponse> convertedGridItems) {
        return new AnnouncementDetailResponse(
                id, travelerId, departureCity, arrivalCity, departureDate, departureTime, arrivalTime,
                pickupAddress, deliveryAddress, availableKg, totalKg, pricePerKg, pricePerKgDisplay,
                transportMode, status, bidsCount, confirmedParcelCount, traveler, description,
                acceptedContentTypes, refusedTypes, acceptedPaymentMethods, capacityUnit, cashAccepted,
                createdAt, updatedAt, pricingMode, convertedGridItems, reservedKg, surplusEligible,
                surplusPublished, handoverDeadline, currency, arrivalInstructions, negotiable,
                availablePaymentMethods, convertedPricePerKg, pricePerKgDisplayConverted, convertedCurrency);
    }
}
