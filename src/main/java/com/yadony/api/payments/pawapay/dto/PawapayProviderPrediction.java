package com.yadony.api.payments.pawapay.dto;

/** Résultat de {@code POST /v2/predict-provider} : opérateur déduit d'un numéro de téléphone. */
public record PawapayProviderPrediction(String countryAlpha3, String provider, String phoneNumber) {}
