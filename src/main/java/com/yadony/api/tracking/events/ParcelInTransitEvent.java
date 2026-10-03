package com.yadony.api.tracking.events;

import java.util.UUID;

/**
 * Publié par {@code TrackingService} quand un colis passe en transit (scan
 * TRANSIT). Le trajet est alors parti : {@code matching} le retire du marché
 * pour qu'il n'accepte plus de nouvelles demandes (FLUTTER-AE).
 */
public record ParcelInTransitEvent(UUID bidId, UUID announcementId) {}
