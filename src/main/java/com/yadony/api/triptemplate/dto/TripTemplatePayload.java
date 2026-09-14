package com.yadony.api.triptemplate.dto;

import com.yadony.api.matching.dto.AddressDto;
import com.yadony.api.payments.cash.PaymentMethod;

import java.time.LocalTime;
import java.util.List;
import java.util.Set;

/**
 * Champs communs aux requêtes de création et de mise à jour d'un modèle de trajet.
 * Les deux records sont identiques : le service ne lit que cette interface.
 */
public interface TripTemplatePayload {
    String label();
    String emoji();
    String departureCity();
    Double departureLat();
    Double departureLng();
    String arrivalCity();
    Double arrivalLat();
    Double arrivalLng();
    String transportMode();
    String capacityUnit();
    Integer availableKg();
    Double pricePerKg();
    List<String> acceptedCategories();
    boolean cashAccepted();
    LocalTime arrivalTime();
    String currency();
    String pricingMode();
    Set<PaymentMethod> acceptedPaymentMethods();
    Boolean negotiable();
    List<String> refusedTypes();
    String description();
    AddressDto pickupAddress();
    AddressDto deliveryAddress();
    LocalTime departureTime();
    Integer handoverLeadDays();
    String departureCountryCode();
    String arrivalCountryCode();
}
