package com.yadony.api.triptemplate.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.yadony.api.matching.dto.AddressDto;
import com.yadony.api.payments.cash.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.LocalTime;
import java.util.List;
import java.util.Set;

// Le plafond réel du prix dépend de la devise du modèle et est appliqué par
// TripTemplateService.assertPricePerKg : une annotation ne peut pas
// en dépendre. Seul un garde-fou anti-abus subsiste ici. Le prix est optionnel
// (mode MIXED, grille seule) : le service exige un prix > 0 en mode KG.
public record CreateTripTemplateRequest(
    @NotBlank @Size(max = 60)  String label,
    @Size(max = 8)             String emoji,
    @NotBlank @Size(max = 100) String departureCity,
    Double departureLat,
    Double departureLng,
    @NotBlank @Size(max = 100) String arrivalCity,
    Double arrivalLat,
    Double arrivalLng,
    @NotBlank @Size(max = 20)  String transportMode,
    @NotBlank @Size(max = 20)  String capacityUnit,
    // Pas de plafond : deux valises de 32 kg font 64 kg, et l'annonce n'en a aucun.
    @NotNull @Min(1) Integer availableKg,
    @DecimalMin("0.0") @DecimalMax("1000000.0") Double pricePerKg,
    List<String> acceptedCategories,
    // Miroir historique de acceptedPaymentMethods (contient CASH). Un client ancien
    // n'envoie que ce champ : le service en dérive alors les moyens de paiement.
    boolean cashAccepted,
    @JsonFormat(pattern = "HH:mm") LocalTime arrivalTime,
    @Size(min = 3, max = 3) String currency,
    @Pattern(regexp = "KG|MIXED") String pricingMode,
    Set<PaymentMethod> acceptedPaymentMethods,
    Boolean negotiable,
    List<String> refusedTypes,
    @Size(max = 500) String description,
    @Valid AddressDto pickupAddress,
    @Valid AddressDto deliveryAddress,
    @JsonFormat(pattern = "HH:mm") LocalTime departureTime,
    @Min(0) @Max(7) Integer handoverLeadDays,
    @Pattern(regexp = "[A-Za-z]{2}") String departureCountryCode,
    @Pattern(regexp = "[A-Za-z]{2}") String arrivalCountryCode
) implements TripTemplatePayload {}
