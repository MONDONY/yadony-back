package com.yadony.api.matching.dto;

import java.util.List;
import java.util.UUID;

/** Voyage créé : l'identifiant commun et chaque étape, dans l'ordre du voyage. */
public record TripResponse(UUID tripGroupId, List<AnnouncementResponse> legs) {}
