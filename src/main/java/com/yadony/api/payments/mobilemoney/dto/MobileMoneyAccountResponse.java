package com.yadony.api.payments.mobilemoney.dto;

import java.time.Instant;

/**
 * État du compte de versement mobile money du voyageur, tel qu'exposé à l'app.
 *
 * <p>Ne porte jamais le numéro en clair — seulement sa forme masquée
 * ({@link com.yadony.api.common.Msisdn#mask(String)}). C'est une règle absolue du
 * projet : le MSISDN est la destination de l'argent, il ne doit jamais pouvoir
 * fuiter par une réponse d'API, un log ou une trace d'erreur.
 */
public record MobileMoneyAccountResponse(String status, String msisdnMasked, String provider, String providerLabel,
                                         String country, String currency, Instant verifiedAt) {}
