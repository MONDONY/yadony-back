package com.yadony.api.travelerstatus.dto;

import com.yadony.api.travelerstatus.TravelerStatus;

import java.time.Instant;

/** Statut déclaré, renvoyé à son propriétaire (qui voit son propre numéro d'immatriculation). */
public record TravelerStatusResponse(
        TravelerStatus status,
        Instant declaredAt,
        String businessName,
        String registrationNumber,
        String country
) {
}
