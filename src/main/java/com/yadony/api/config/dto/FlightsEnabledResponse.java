package com.yadony.api.config.dto;

/** Feature flag des billets d'avion, lu par l'application au démarrage. */
public record FlightsEnabledResponse(boolean enabled) {}
