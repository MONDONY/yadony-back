package com.yadony.api.assistant.provider.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Tous les champs du formulaire. {@code apiKey} null, vide ou seulement des espaces = clé inchangée,
 * sinon 8 à 500 caractères ASCII visibles sans espace ;
 * {@code dailyRequestLimit} null = sans limite.
 */
public record UpdateProviderRequest(
        @NotBlank @Size(max = 60) String name,
        @NotBlank String baseUrl,
        @NotBlank @Size(max = 120) String model,
        @Pattern(regexp = "^\\s*$|^\\s*[\\x21-\\x7E]{8,500}\\s*$", message = API_KEY_MESSAGE) String apiKey,
        boolean supportsJsonMode,
        @Min(1) @Max(1_000_000) Integer dailyRequestLimit,
        boolean enabled) {

    static final String API_KEY_MESSAGE = "La clé doit faire de 8 à 500 caractères visibles, sans espace.";

    /** Jamais la clé dans les journaux ni dans un message d'erreur. */
    @Override
    public String toString() {
        return "UpdateProviderRequest[name=" + name + ", baseUrl=" + baseUrl + ", model=" + model
                + ", apiKey=****, supportsJsonMode=" + supportsJsonMode
                + ", dailyRequestLimit=" + dailyRequestLimit + ", enabled=" + enabled + "]";
    }
}
