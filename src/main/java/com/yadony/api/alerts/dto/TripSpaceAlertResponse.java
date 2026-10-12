package com.yadony.api.alerts.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/** Abonnement « Me prévenir si de la place se libère » en attente (FLUTTER-JH). */
public record TripSpaceAlertResponse(UUID announcementId, LocalDateTime createdAt) {}
