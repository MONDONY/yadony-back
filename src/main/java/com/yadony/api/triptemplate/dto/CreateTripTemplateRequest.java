package com.yadony.api.triptemplate.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.yadony.api.matching.dto.AddressDto;
import com.yadony.api.matching.dto.ClearableMaxParcelWeight;
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
    @Pattern(regexp = "[A-Za-z]{2}") String arrivalCountryCode,
    // Jour d'arrivée relatif au départ (FLUTTER-4E). Absent = même jour. Plafond du mode (3 en
    // avion, 14 en route, 120 en bateau) contrôlé par le service (422 arrival-too-far).
    @Min(0) @Max(120) Integer arrivalDayOffset,
    // Taille maximale d'un colis (route : voiture, bus) et poids maximal par colis (route et bateau),
    // facultatifs : mêmes règles que sur un trajet (AnnouncementRequest). Création : absent ou
    // « NONE »/0 = aucune limite. Modification : ABSENT = valeur conservée (client antérieur) ;
    // « NONE »/0 = effacée. Normalisés selon le mode par le service (ParcelFitRules).
    @Pattern(regexp = "NONE|S|M|L|XL", message = "{validation.trip.max-item-size.pattern}")
    String maxItemSize,
    @ClearableMaxParcelWeight
    @DecimalMax(value = "999999.99", message = "{validation.trip.max-parcel-weight.max}")
    Double maxParcelWeightKg
) implements TripTemplatePayload {

    /** Arité historique (sans jour d'arrivée), conservée pour les appels existants. */
    public CreateTripTemplateRequest(String label, String emoji, String departureCity, Double departureLat,
                                     Double departureLng, String arrivalCity, Double arrivalLat, Double arrivalLng,
                                     String transportMode, String capacityUnit, Integer availableKg,
                                     Double pricePerKg, List<String> acceptedCategories, boolean cashAccepted,
                                     LocalTime arrivalTime, String currency, String pricingMode,
                                     Set<PaymentMethod> acceptedPaymentMethods, Boolean negotiable,
                                     List<String> refusedTypes, String description, AddressDto pickupAddress,
                                     AddressDto deliveryAddress, LocalTime departureTime,
                                     Integer handoverLeadDays, String departureCountryCode,
                                     String arrivalCountryCode) {
        this(label, emoji, departureCity, departureLat, departureLng, arrivalCity, arrivalLat, arrivalLng,
                transportMode, capacityUnit, availableKg, pricePerKg, acceptedCategories, cashAccepted,
                arrivalTime, currency, pricingMode, acceptedPaymentMethods, negotiable, refusedTypes,
                description, pickupAddress, deliveryAddress, departureTime, handoverLeadDays,
                departureCountryCode, arrivalCountryCode, null, null, null);
    }

    /** Arité d'avant la taille et le poids maximaux, conservée pour les appels existants. */
    public CreateTripTemplateRequest(String label, String emoji, String departureCity, Double departureLat,
                                     Double departureLng, String arrivalCity, Double arrivalLat, Double arrivalLng,
                                     String transportMode, String capacityUnit, Integer availableKg,
                                     Double pricePerKg, List<String> acceptedCategories, boolean cashAccepted,
                                     LocalTime arrivalTime, String currency, String pricingMode,
                                     Set<PaymentMethod> acceptedPaymentMethods, Boolean negotiable,
                                     List<String> refusedTypes, String description, AddressDto pickupAddress,
                                     AddressDto deliveryAddress, LocalTime departureTime,
                                     Integer handoverLeadDays, String departureCountryCode,
                                     String arrivalCountryCode, Integer arrivalDayOffset) {
        this(label, emoji, departureCity, departureLat, departureLng, arrivalCity, arrivalLat, arrivalLng,
                transportMode, capacityUnit, availableKg, pricePerKg, acceptedCategories, cashAccepted,
                arrivalTime, currency, pricingMode, acceptedPaymentMethods, negotiable, refusedTypes,
                description, pickupAddress, deliveryAddress, departureTime, handoverLeadDays,
                departureCountryCode, arrivalCountryCode, arrivalDayOffset, null, null);
    }
}
