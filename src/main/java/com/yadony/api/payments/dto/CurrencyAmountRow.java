package com.yadony.api.payments.dto;

import java.math.BigDecimal;

/**
 * Somme agrégée par devise (code ISO majuscule), projection commune des revenus
 * voyageur carte ({@code PaymentRepository}) et espèces ({@code BidRepository}).
 *
 * <p>Additionner des devises entre elles est le bug que ce record ferme : un
 * voyageur payé en EUR et en XOF voyait « 8 + 5000 » affiché en euros. Chaque
 * consommateur reçoit la ventilation et convertit lui-même vers la devise active
 * du lecteur (au taux courant, avec « environ »), jamais l'inverse.
 */
public record CurrencyAmountRow(String currency, BigDecimal amount) {}
