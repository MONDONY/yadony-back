package com.yadony.api.requests.dto;

import java.util.List;
import java.util.UUID;

/** Chiffres réservés au propriétaire de la demande. */
public record PackageRequestInsightsResponse(long viewCount, List<UUID> invitedAnnouncementIds) {}
