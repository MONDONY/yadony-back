package com.yadony.api.admin.dto;

import java.util.List;
import java.util.UUID;

/** Propositions de recherche des colis, cinq au plus par groupe ; jamais de téléphone. */
public record AdminBidSuggestResponse(List<Person> senders, List<Person> travelers, List<Recipient> recipients,
                                      List<Trip> trips, List<Parcel> parcels) {

    public record Person(UUID id, String name, long count) {
    }

    public record Recipient(String name, long count) {
    }

    public record Trip(String label, String departureCity, String arrivalCity, long count) {
    }

    public record Parcel(UUID bidId, String trackingNumber, String status, String content) {
    }

    public static AdminBidSuggestResponse empty() {
        return new AdminBidSuggestResponse(List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
