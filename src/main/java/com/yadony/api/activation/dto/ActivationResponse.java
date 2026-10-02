package com.yadony.api.activation.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Réponse de GET /users/me/activation : de quoi guider l'utilisateur après son KYC. */
public record ActivationResponse(
        String intent,
        String destinationCountry,
        boolean kycVerified,
        boolean firstActionDone,
        Opportunities opportunities) {

    /** kind : TRIPS (expéditeur, les deux), PACKAGES (voyageur) ou NONE (intention ou pays inconnus). */
    public record Opportunities(String kind, long total, List<TripItem> trips, List<PackageItem> packages) {
        public static Opportunities none() { return new Opportunities("NONE", 0, List.of(), List.of()); }
    }

    public record TripItem(UUID id, String departureCity, String arrivalCity, LocalDate departureDate,
                           Double availableKg, Double pricePerKg, String currency) {}

    public record PackageItem(UUID id, String departureCity, String arrivalCity, LocalDate desiredDate,
                              Double weightKg) {}
}
