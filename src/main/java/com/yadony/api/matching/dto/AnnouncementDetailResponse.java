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
        Set<PaymentMethod> availablePaymentMethods
) {}
