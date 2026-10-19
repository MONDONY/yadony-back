package com.yadony.api.payments.dto;

/**
 * Lien à usage unique vers le tableau de bord Stripe Express du voyageur (changer de compte
 * bancaire, voir les virements). À ouvrir tout de suite dans le navigateur, jamais en cache.
 */
public record DashboardLinkResponse(String url) {}
