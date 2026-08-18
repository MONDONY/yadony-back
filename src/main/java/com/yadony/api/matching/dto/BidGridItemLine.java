package com.yadony.api.matching.dto;

import java.math.BigDecimal;
import java.util.UUID;

/** Ligne de grille telle qu'affichée dans le fil. Le prix montré est le prix EXPÉDITEUR. */
public record BidGridItemLine(UUID id, String label, BigDecimal unitPriceDisplayEur, int quantity) {}
