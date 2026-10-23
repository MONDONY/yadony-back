package com.yadony.api.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/**
 * Propositions de recherche des paiements, cinq au plus par groupe ; jamais de téléphone.
 * {@code exactPaymentId} n'est présent que si la saisie identifie exactement un paiement.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminPaymentSuggestResponse(UUID exactPaymentId, List<Person> senders, List<Person> travelers,
                                          List<Trip> trips, List<Parcel> parcels) {

    public record Person(UUID id, String name, long count) {
    }

    public record Trip(String label, String departureCity, String arrivalCity, long count) {
    }

    public record Parcel(UUID paymentId, String trackingNumber, String status, long amountCents, String currency) {
    }

    public static AdminPaymentSuggestResponse empty() {
        return new AdminPaymentSuggestResponse(null, List.of(), List.of(), List.of(), List.of());
    }
}
