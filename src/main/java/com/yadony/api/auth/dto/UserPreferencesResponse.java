package com.yadony.api.auth.dto;

/**
 * Réponse {@code PATCH /users/me/preferences}.
 *
 * @param language langue enregistrée, {@code "fr"} ou {@code "en"}.
 */
public record UserPreferencesResponse(String language) {}
