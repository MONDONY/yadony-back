package com.yadony.api.matching.dto;

import com.yadony.api.payments.cash.PaymentMethod;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

public record AnnouncementRequest(
        @NotBlank(message = "{validation.trip.departure-city.required}")
        String departureCity,

        @NotBlank(message = "{validation.trip.arrival-city.required}")
        String arrivalCity,

        @NotNull(message = "{validation.trip.departure-date.required}")
        @FutureOrPresent(message = "{validation.trip.departure-date.past}")
        LocalDate departureDate,

        @NotNull(message = "{validation.trip.departure-time.required}")
        @JsonFormat(pattern = "HH:mm")
        LocalTime departureTime,

        @JsonFormat(pattern = "HH:mm")
        LocalTime arrivalTime,

        @Valid @NotNull(message = "{validation.trip.pickup-address.required}")
        AddressDto pickupAddress,

        @Valid @NotNull(message = "{validation.trip.delivery-address.required}")
        AddressDto deliveryAddress,

        @NotNull(message = "{validation.trip.available-kg.required}")
        @DecimalMin(value = "1.0", message = "{validation.trip.available-kg.min}")
        BigDecimal availableKg,

        // Nullable en mode MIXED (grille seule) ; validé côté service si mode KG
        @DecimalMin(value = "0.0", message = "{validation.trip.price.negative}")
        BigDecimal pricePerKg,

        @NotNull(message = "{validation.trip.transport-mode.required}")
        com.yadony.api.matching.TransportMode transportMode,

        @Size(max = 500, message = "{validation.trip.note.max}")
        String description,

        List<String> acceptedContentTypes,

        List<String> refusedTypes,

        Set<PaymentMethod> acceptedPaymentMethods,

        com.yadony.api.matching.CapacityUnit capacityUnit,

        com.yadony.api.matching.PricingMode pricingMode,

        @Size(max = 2, message = "{validation.trip.departure-country.size}")
        String departureCountryCode,

        @Size(max = 2, message = "{validation.trip.arrival-country.size}")
        String arrivalCountryCode,

        // Date limite de dépôt — obligatoire (validée dans AnnouncementService).
        // Pas de @JsonFormat : reçoit un ISO-8601 (ex "2026-06-14T18:00:00.000Z").
        LocalDateTime handoverDeadline,

        // Brouillon : si true, l'annonce est créée en statut DRAFT (skip KYC + limite mensuelle,
        // soumise au quota de brouillons — cf AnnouncementService.createAnnouncement).
        Boolean saveAsDraft,

        // Le voyageur ouvre son trajet aux propositions de prix. Nullable pour les
        // clients pas encore à jour : absent = prix ferme, le comportement historique.
        Boolean negotiable,

        // Devise choisie pour ce trajet (ex "USD"). Optionnel : absent ou vide, le
        // service retombe sur ActiveCurrencyResolver.resolve(travelerId) (portefeuille,
        // sinon pays). Validée contre SupportedCurrency dans AnnouncementService,
        // jamais ici (422 currency-unsupported, pas une 400 Bean Validation).
        // Construit positionnellement dans les tests : voir le constructeur compatible.
        String currency,
        // Date d'arrivée si elle diffère du départ (vol de nuit) ; absente = même jour.
        // Validée par ArrivalRules (FLUTTER-4E).
        LocalDate arrivalDate,
        // Escales d'un trajet en avion (FLUTTER-GE) : 0 direct, 1, 2 = deux ou plus.
        // Optionnel ; validé et ignoré hors avion par TripStops. Absent à la modification =
        // valeur conservée (client antérieur).
        Integer stopsCount
) {
    /** Arité sans escales, conservée pour les appels existants. */
    public AnnouncementRequest(String departureCity, String arrivalCity, LocalDate departureDate,
                               LocalTime departureTime, LocalTime arrivalTime,
                               AddressDto pickupAddress, AddressDto deliveryAddress,
                               BigDecimal availableKg, BigDecimal pricePerKg,
                               com.yadony.api.matching.TransportMode transportMode, String description,
                               List<String> acceptedContentTypes, List<String> refusedTypes,
                               Set<PaymentMethod> acceptedPaymentMethods,
                               com.yadony.api.matching.CapacityUnit capacityUnit,
                               com.yadony.api.matching.PricingMode pricingMode,
                               String departureCountryCode, String arrivalCountryCode,
                               LocalDateTime handoverDeadline, Boolean saveAsDraft,
                               Boolean negotiable, String currency, LocalDate arrivalDate) {
        this(departureCity, arrivalCity, departureDate, departureTime, arrivalTime, pickupAddress,
                deliveryAddress, availableKg, pricePerKg, transportMode, description,
                acceptedContentTypes, refusedTypes, acceptedPaymentMethods, capacityUnit, pricingMode,
                departureCountryCode, arrivalCountryCode, handoverDeadline, saveAsDraft, negotiable,
                currency, arrivalDate, null);
    }

    /** Arité historique (sans date d'arrivée), conservée pour les appels existants. */
    public AnnouncementRequest(String departureCity, String arrivalCity, LocalDate departureDate,
                               LocalTime departureTime, LocalTime arrivalTime,
                               AddressDto pickupAddress, AddressDto deliveryAddress,
                               BigDecimal availableKg, BigDecimal pricePerKg,
                               com.yadony.api.matching.TransportMode transportMode, String description,
                               List<String> acceptedContentTypes, List<String> refusedTypes,
                               Set<PaymentMethod> acceptedPaymentMethods,
                               com.yadony.api.matching.CapacityUnit capacityUnit,
                               com.yadony.api.matching.PricingMode pricingMode,
                               String departureCountryCode, String arrivalCountryCode,
                               LocalDateTime handoverDeadline, Boolean saveAsDraft,
                               Boolean negotiable, String currency) {
        this(departureCity, arrivalCity, departureDate, departureTime, arrivalTime, pickupAddress,
                deliveryAddress, availableKg, pricePerKg, transportMode, description,
                acceptedContentTypes, refusedTypes, acceptedPaymentMethods, capacityUnit, pricingMode,
                departureCountryCode, arrivalCountryCode, handoverDeadline, saveAsDraft, negotiable,
                currency, null, null);
    }

    public boolean isDraft() {
        return Boolean.TRUE.equals(saveAsDraft);
    }

    public boolean isNegotiable() {
        return Boolean.TRUE.equals(negotiable);
    }
}
