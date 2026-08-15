package com.yadony.api.requests.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// Le plafond réel dépend de la devise du fil et est appliqué par
// NegotiationService.assertPriceWithinBounds : une annotation ne peut pas le
// porter. Ici on ne garde qu'un garde-fou anti-abus, assez large pour laisser
// passer les devises sans sous-unité (327 500 XOF au plafond).
//
// Trajet obligatoire dès l'offre (cf. spec 2026-08-16) : soit
// travelerAnnouncementId pointe un trajet existant, soit createDedicatedTrip
// est true et dedicatedTrip porte les champs de création. Le service vérifie
// qu'exactement un des deux est fourni (422 trip-required sinon) — un record
// ne peut pas exprimer un XOR en Bean Validation pur.
public record NegotiationStartRequest(
    @NotNull UUID packageRequestId,
    @NotNull @DecimalMin("0.01") @DecimalMax("1000000.0") BigDecimal proposedPriceEur,
    @NotNull LocalDate travelerTravelDate,
    @NotNull @DecimalMin("0.01") BigDecimal travelerAvailableKg,
    UUID travelerAnnouncementId,
    @Size(max = 280) String body,
    boolean createDedicatedTrip,
    @Valid NegotiationCreateDedicatedTripRequest dedicatedTrip
) {
    public NegotiationStartRequest(UUID packageRequestId, BigDecimal proposedPriceEur,
                                    LocalDate travelerTravelDate, BigDecimal travelerAvailableKg,
                                    UUID travelerAnnouncementId, String body) {
        this(packageRequestId, proposedPriceEur, travelerTravelDate, travelerAvailableKg,
             travelerAnnouncementId, body, false, null);
    }
}
