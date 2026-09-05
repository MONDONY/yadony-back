package com.yadony.api.kyc.provider;

/** Session de verification ouverte chez un fournisseur : sa page hebergee et son identifiant. */
public record ProviderSession(String url, String sessionId) {}
