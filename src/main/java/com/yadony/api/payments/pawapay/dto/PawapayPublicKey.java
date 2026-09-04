package com.yadony.api.payments.pawapay.dto;

/** Une clé publique de {@code GET /v2/public-key/http}, utilisée pour vérifier la signature des callbacks. */
public record PawapayPublicKey(String id, String pem) {}
