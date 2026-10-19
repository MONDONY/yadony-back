package com.yadony.api.config.dto;

/**
 * Moyens de paiement que l'application peut proposer dans sa feuille de paiement, lus à chaque
 * ouverture de la feuille ({@code GET /config/payment-methods}). Un champ absent (ancien serveur)
 * vaut faux côté application : elle ne propose alors que la carte.
 */
public record PaymentMethodsResponse(boolean applePayEnabled, boolean googlePayEnabled, boolean paypalEnabled) {}
