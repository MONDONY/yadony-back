package com.yadony.api.requests.dto;

import com.yadony.api.matching.TransportMode;
import com.yadony.api.matching.dto.AddressDto;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Body posted by the traveler to {@code POST /negotiations/{id}/create-dedicated-trip}
 * when no existing announcement matches and the traveler creates a brand-new
 * trip dedicated to that single package_request.
 *
 * Locked fields (corridor, weight, transport mode, total agreed price) are NOT
 * in this DTO — they are derived server-side from the negotiating thread and
 * its underlying package_request. Only fields the traveler can edit are listed.
 *
 * Le mode de paiement n'y figure pas non plus : il est défini par l'expéditeur
 * (package_request.acceptedPaymentMethods) et arrêté par lui au checkout.
 */
public record NegotiationCreateDedicatedTripRequest(
        @NotNull(message = "{validation.trip.departure-date.required}")
        @FutureOrPresent(message = "{validation.trip.departure-date.past}")
        LocalDate departureDate,

        @JsonFormat(pattern = "HH:mm")
        LocalTime departureTime,

        @JsonFormat(pattern = "HH:mm")
        LocalTime arrivalTime,

        @Valid @NotNull(message = "{validation.trip.pickup-address.required}")
        AddressDto pickupAddress,

        @Valid @NotNull(message = "{validation.trip.delivery-address.required}")
        AddressDto deliveryAddress,

        @Size(max = 500, message = "{validation.trip.note.max}")
        String description,

        List<String> acceptedContentTypes,

        List<String> refusedTypes,

        // CASH uniquement : si true, le voyageur consent à payer la commission sur
        // sa carte quand son wallet est insuffisant. Absent du JSON → false.
        boolean useCardForCommission,

        // Date d'arrivée si différente du départ (vol de nuit) ; absente = même jour
        // (FLUTTER-4E).
        LocalDate arrivalDate,

        // Mode du trajet dédié quand la demande de colis ne fixe pas le sien (« peu importe ») ;
        // ignoré dès que la demande en porte un. Absent : avion (anciennes apps ; la colonne est NOT NULL).
        TransportMode transportMode,

        // Fin de la fourchette d'arrivée estimée (bateau, lot 3) : obligatoire pour un trajet dédié bateau
        // (422 arrival-range-invalid), avec arrivalDate comme début ; ignorée pour tout autre mode.
        LocalDate arrivalDateMax
) {
    public NegotiationCreateDedicatedTripRequest(
            LocalDate departureDate, LocalTime departureTime, LocalTime arrivalTime,
            AddressDto pickupAddress, AddressDto deliveryAddress, String description,
            List<String> acceptedContentTypes, List<String> refusedTypes,
            boolean useCardForCommission, LocalDate arrivalDate, TransportMode transportMode) {
        this(departureDate, departureTime, arrivalTime, pickupAddress, deliveryAddress,
             description, acceptedContentTypes, refusedTypes, useCardForCommission, arrivalDate,
             transportMode, null);
    }

    public NegotiationCreateDedicatedTripRequest(
            LocalDate departureDate, LocalTime departureTime, LocalTime arrivalTime,
            AddressDto pickupAddress, AddressDto deliveryAddress, String description,
            List<String> acceptedContentTypes, List<String> refusedTypes) {
        this(departureDate, departureTime, arrivalTime, pickupAddress, deliveryAddress,
             description, acceptedContentTypes, refusedTypes, false, null, null);
    }

    public NegotiationCreateDedicatedTripRequest(
            LocalDate departureDate, LocalTime departureTime, LocalTime arrivalTime,
            AddressDto pickupAddress, AddressDto deliveryAddress, String description,
            List<String> acceptedContentTypes, List<String> refusedTypes,
            boolean useCardForCommission) {
        this(departureDate, departureTime, arrivalTime, pickupAddress, deliveryAddress,
             description, acceptedContentTypes, refusedTypes, useCardForCommission, null, null, null);
    }

    public NegotiationCreateDedicatedTripRequest(
            LocalDate departureDate, LocalTime departureTime, LocalTime arrivalTime,
            AddressDto pickupAddress, AddressDto deliveryAddress, String description,
            List<String> acceptedContentTypes, List<String> refusedTypes,
            boolean useCardForCommission, LocalDate arrivalDate) {
        this(departureDate, departureTime, arrivalTime, pickupAddress, deliveryAddress,
             description, acceptedContentTypes, refusedTypes, useCardForCommission, arrivalDate, null, null);
    }
}
