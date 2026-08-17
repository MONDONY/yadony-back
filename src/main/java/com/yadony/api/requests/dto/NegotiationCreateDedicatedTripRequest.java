package com.yadony.api.requests.dto;

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
        @NotNull(message = "La date de départ est obligatoire")
        @FutureOrPresent(message = "La date de départ ne peut pas être dans le passé")
        LocalDate departureDate,

        @JsonFormat(pattern = "HH:mm")
        LocalTime departureTime,

        @JsonFormat(pattern = "HH:mm")
        LocalTime arrivalTime,

        @Valid @NotNull(message = "L'adresse de remise est obligatoire")
        AddressDto pickupAddress,

        @Valid @NotNull(message = "L'adresse de récupération est obligatoire")
        AddressDto deliveryAddress,

        @Size(max = 500, message = "La note ne peut pas dépasser 500 caractères")
        String description,

        List<String> acceptedContentTypes,

        List<String> refusedTypes,

        // CASH uniquement : si true, le voyageur consent à payer la commission sur
        // sa carte quand son wallet est insuffisant. Absent du JSON → false.
        boolean useCardForCommission
) {
    public NegotiationCreateDedicatedTripRequest(
            LocalDate departureDate, LocalTime departureTime, LocalTime arrivalTime,
            AddressDto pickupAddress, AddressDto deliveryAddress, String description,
            List<String> acceptedContentTypes, List<String> refusedTypes) {
        this(departureDate, departureTime, arrivalTime, pickupAddress, deliveryAddress,
             description, acceptedContentTypes, refusedTypes, false);
    }
}
