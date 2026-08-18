package com.yadony.api.matching.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Ligne hors grille telle qu'affichée dans le fil. {@code amountEur} est UNITAIRE. */
public record BidCustomItemResponse(UUID id, String label, int quantity, BigDecimal amountEur) {}
