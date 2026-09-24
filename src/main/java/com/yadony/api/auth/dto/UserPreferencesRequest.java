package com.yadony.api.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Requête {@code PATCH /users/me/preferences}.
 *
 * @param language {@code "fr"} ou {@code "en"}. Toute autre valeur, ou son
 *        absence, est refusée en 422 avec un message traduit selon
 *        l'{@code Accept-Language} de la requête (D3 du plan i18n).
 */
public record UserPreferencesRequest(
        @NotBlank(message = "{validation.language.required}")
        @Pattern(regexp = "fr|en", message = "{validation.language.unsupported}")
        String language) {}
