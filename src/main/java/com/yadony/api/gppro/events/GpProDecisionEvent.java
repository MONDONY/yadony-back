package com.yadony.api.gppro.events;

import java.util.UUID;

/** Décision de l'admin sur une demande GP Pro, ou retrait du badge. */
public record GpProDecisionEvent(UUID userId, Kind kind, String reason) {

    public enum Kind { APPROVED, REJECTED, REVOKED }
}
