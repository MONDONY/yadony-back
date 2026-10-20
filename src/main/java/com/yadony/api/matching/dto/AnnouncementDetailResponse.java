package com.yadony.api.matching.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.yadony.api.matching.TravelerPayoutNetworksPort.PayoutNetwork;
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
        /** Date d'arrivée si différente du départ (vol de nuit) ; null = même jour. */
        LocalDate arrivalDate,
        AddressDto pickupAddress,
        AddressDto deliveryAddress,
        BigDecimal availableKg,
        BigDecimal totalKg,
        BigDecimal pricePerKg,
        BigDecimal pricePerKgDisplay,
        com.yadony.api.matching.TransportMode transportMode,
        /** Escales d'un trajet en avion (FLUTTER-GE) : 0 direct, 1, 2 = deux ou plus ; null = non renseigné. */
        Integer stopsCount,
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
        String convertedCurrency,
        /** Reports encore possibles pour ce trajet (vol annulé, voyage repoussé), sur
         *  {@link com.yadony.api.matching.TripRescheduleRules#MAX_RESCHEDULES}. Servi au seul
         *  voyageur propriétaire ; null (donc absent du JSON) pour tout autre lecteur. */
        Integer remainingReschedules,
        /** Code pays ISO-2 du départ, lu par l'édition du trajet pour le renvoyer tel quel
         *  (FLUTTER-EH : sans lui, chaque modification effaçait le pays en base). */
        String departureCountryCode,
        /** Code pays ISO-2 de l'arrivée, même usage que {@link #departureCountryCode}. */
        String arrivalCountryCode,
        /**
         * La devise du trajet peut encore être changée par {@code PUT /announcements/{id}}
         * (champ {@code currency}) : aucune demande, offre ni négociation vivante dessus
         * (FLUTTER-KV). Servi au seul voyageur propriétaire ; null (donc absent du JSON) pour
         * tout autre lecteur.
         */
        Boolean currencyEditable,
        /**
         * Réseaux mobile money où le voyageur reçoit ses versements dans la devise du trajet
         * ({@code code}, {@code label}, {@code brand}, {@code country}), le plus ancien compte
         * d'abord. Vide, jamais null, quand le mobile money n'est pas offert sur ce trajet. Jamais
         * de numéro.
         */
        List<PayoutNetwork> acceptedMobileMoneyNetworks
) {
    public AnnouncementDetailResponse {
        acceptedMobileMoneyNetworks = acceptedMobileMoneyNetworks == null ? List.of() : acceptedMobileMoneyNetworks;
    }

    /** Sans {@code acceptedMobileMoneyNetworks} (liste vide) : appelants qui ne le calculent pas. */
    public AnnouncementDetailResponse(
            UUID id, UUID travelerId, String departureCity, String arrivalCity, LocalDate departureDate,
            LocalTime departureTime, LocalTime arrivalTime, LocalDate arrivalDate,
            AddressDto pickupAddress, AddressDto deliveryAddress, BigDecimal availableKg, BigDecimal totalKg,
            BigDecimal pricePerKg, BigDecimal pricePerKgDisplay,
            com.yadony.api.matching.TransportMode transportMode, Integer stopsCount, String status,
            long bidsCount, long confirmedParcelCount, TravelerProfileDto traveler, String description,
            List<String> acceptedContentTypes, List<String> refusedTypes, List<String> acceptedPaymentMethods,
            com.yadony.api.matching.CapacityUnit capacityUnit, boolean cashAccepted,
            LocalDateTime createdAt, LocalDateTime updatedAt, com.yadony.api.matching.PricingMode pricingMode,
            List<AnnouncementPriceGridItemResponse> priceGridItems, BigDecimal reservedKg,
            boolean surplusEligible, boolean surplusPublished, LocalDateTime handoverDeadline,
            String currency, String arrivalInstructions, boolean negotiable,
            Set<PaymentMethod> availablePaymentMethods, BigDecimal convertedPricePerKg,
            BigDecimal pricePerKgDisplayConverted, String convertedCurrency, Integer remainingReschedules,
            String departureCountryCode, String arrivalCountryCode, Boolean currencyEditable) {
        this(id, travelerId, departureCity, arrivalCity, departureDate, departureTime, arrivalTime, arrivalDate,
                pickupAddress, deliveryAddress, availableKg, totalKg, pricePerKg, pricePerKgDisplay,
                transportMode, stopsCount, status, bidsCount, confirmedParcelCount, traveler, description,
                acceptedContentTypes, refusedTypes, acceptedPaymentMethods, capacityUnit, cashAccepted,
                createdAt, updatedAt, pricingMode, priceGridItems, reservedKg, surplusEligible,
                surplusPublished, handoverDeadline, currency, arrivalInstructions, negotiable,
                availablePaymentMethods, convertedPricePerKg, pricePerKgDisplayConverted, convertedCurrency,
                remainingReschedules, departureCountryCode, arrivalCountryCode, currencyEditable, List.of());
    }

    /** Ancienne arité, sans {@code currencyEditable} (null) : appelants qui ne le calculent pas. */
    public AnnouncementDetailResponse(
            UUID id, UUID travelerId, String departureCity, String arrivalCity, LocalDate departureDate,
            LocalTime departureTime, LocalTime arrivalTime, LocalDate arrivalDate,
            AddressDto pickupAddress, AddressDto deliveryAddress, BigDecimal availableKg, BigDecimal totalKg,
            BigDecimal pricePerKg, BigDecimal pricePerKgDisplay,
            com.yadony.api.matching.TransportMode transportMode, Integer stopsCount, String status,
            long bidsCount, long confirmedParcelCount, TravelerProfileDto traveler, String description,
            List<String> acceptedContentTypes, List<String> refusedTypes, List<String> acceptedPaymentMethods,
            com.yadony.api.matching.CapacityUnit capacityUnit, boolean cashAccepted,
            LocalDateTime createdAt, LocalDateTime updatedAt, com.yadony.api.matching.PricingMode pricingMode,
            List<AnnouncementPriceGridItemResponse> priceGridItems, BigDecimal reservedKg,
            boolean surplusEligible, boolean surplusPublished, LocalDateTime handoverDeadline,
            String currency, String arrivalInstructions, boolean negotiable,
            Set<PaymentMethod> availablePaymentMethods, BigDecimal convertedPricePerKg,
            BigDecimal pricePerKgDisplayConverted, String convertedCurrency, Integer remainingReschedules,
            String departureCountryCode, String arrivalCountryCode) {
        this(id, travelerId, departureCity, arrivalCity, departureDate, departureTime, arrivalTime, arrivalDate,
                pickupAddress, deliveryAddress, availableKg, totalKg, pricePerKg, pricePerKgDisplay,
                transportMode, stopsCount, status, bidsCount, confirmedParcelCount, traveler, description,
                acceptedContentTypes, refusedTypes, acceptedPaymentMethods, capacityUnit, cashAccepted,
                createdAt, updatedAt, pricingMode, priceGridItems, reservedKg, surplusEligible,
                surplusPublished, handoverDeadline, currency, arrivalInstructions, negotiable,
                availablePaymentMethods, convertedPricePerKg, pricePerKgDisplayConverted, convertedCurrency,
                remainingReschedules, departureCountryCode, arrivalCountryCode, null, List.of());
    }

    /** Copie portant {@code currencyEditable}, posé pour le seul voyageur propriétaire. */
    public AnnouncementDetailResponse withCurrencyEditable(Boolean currencyEditable) {
        return new AnnouncementDetailResponse(
                id, travelerId, departureCity, arrivalCity, departureDate, departureTime, arrivalTime, arrivalDate,
                pickupAddress, deliveryAddress, availableKg, totalKg, pricePerKg, pricePerKgDisplay,
                transportMode, stopsCount, status, bidsCount, confirmedParcelCount, traveler, description,
                acceptedContentTypes, refusedTypes, acceptedPaymentMethods, capacityUnit, cashAccepted,
                createdAt, updatedAt, pricingMode, priceGridItems, reservedKg, surplusEligible,
                surplusPublished, handoverDeadline, currency, arrivalInstructions, negotiable,
                availablePaymentMethods, convertedPricePerKg, pricePerKgDisplayConverted, convertedCurrency,
                remainingReschedules, departureCountryCode, arrivalCountryCode, currencyEditable,
                acceptedMobileMoneyNetworks);
    }

    /** Copie enrichie des équivalents convertis — même pattern que le fil de recherche. */
    public AnnouncementDetailResponse withConvertedPrices(BigDecimal convertedPricePerKg,
                                                          BigDecimal pricePerKgDisplayConverted,
                                                          String convertedCurrency,
                                                          List<AnnouncementPriceGridItemResponse> convertedGridItems) {
        return new AnnouncementDetailResponse(
                id, travelerId, departureCity, arrivalCity, departureDate, departureTime, arrivalTime, arrivalDate,
                pickupAddress, deliveryAddress, availableKg, totalKg, pricePerKg, pricePerKgDisplay,
                transportMode, stopsCount, status, bidsCount, confirmedParcelCount, traveler, description,
                acceptedContentTypes, refusedTypes, acceptedPaymentMethods, capacityUnit, cashAccepted,
                createdAt, updatedAt, pricingMode, convertedGridItems, reservedKg, surplusEligible,
                surplusPublished, handoverDeadline, currency, arrivalInstructions, negotiable,
                availablePaymentMethods, convertedPricePerKg, pricePerKgDisplayConverted, convertedCurrency,
                remainingReschedules, departureCountryCode, arrivalCountryCode, currencyEditable,
                acceptedMobileMoneyNetworks);
    }

    /** Copie portant les réseaux mobile money acceptés ; {@code null} devient la liste vide. */
    public AnnouncementDetailResponse withMobileMoneyNetworks(List<PayoutNetwork> networks) {
        return new AnnouncementDetailResponse(
                id, travelerId, departureCity, arrivalCity, departureDate, departureTime, arrivalTime, arrivalDate,
                pickupAddress, deliveryAddress, availableKg, totalKg, pricePerKg, pricePerKgDisplay,
                transportMode, stopsCount, status, bidsCount, confirmedParcelCount, traveler, description,
                acceptedContentTypes, refusedTypes, acceptedPaymentMethods, capacityUnit, cashAccepted,
                createdAt, updatedAt, pricingMode, priceGridItems, reservedKg, surplusEligible,
                surplusPublished, handoverDeadline, currency, arrivalInstructions, negotiable,
                availablePaymentMethods, convertedPricePerKg, pricePerKgDisplayConverted, convertedCurrency,
                remainingReschedules, departureCountryCode, arrivalCountryCode, currencyEditable,
                networks == null ? List.of() : networks);
    }
}
