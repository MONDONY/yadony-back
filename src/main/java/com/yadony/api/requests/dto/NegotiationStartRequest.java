package com.yadony.api.requests.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// Aucun plafond métier sur une offre (retiré le 2026-09-19, il bloquait les
// contre-offres réalistes en franc CFA). Le plancher dépend de la devise du fil
// et est appliqué par NegotiationService.assertPriceWithinBounds. Ici on ne
// garde qu'un garde-fou technique anti-abus, aligné sur le CHECK SQL (V256).
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
