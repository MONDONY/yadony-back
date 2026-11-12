package com.yadony.api.config.dto;

/** Interrupteurs des modes de transport route et bateau (l'avion reste toujours ouvert). */
public record TransportModesResponse(boolean road, boolean boat) {}
