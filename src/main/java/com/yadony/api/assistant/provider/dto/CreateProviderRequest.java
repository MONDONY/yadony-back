package com.yadony.api.assistant.provider.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateProviderRequest(
        @NotBlank @Size(max = 60) String name,
        @NotBlank String baseUrl,
        @NotBlank @Size(max = 120) String model,
        @NotBlank @Pattern(regexp = "^\\s*[\\x21-\\x7E]{8,500}\\s*$", message = API_KEY_MESSAGE) String apiKey,
        boolean supportsJsonMode,
        @Min(1) @Max(1_000_000) Integer dailyRequestLimit,
        boolean enabled) {

    static final String API_KEY_MESSAGE = "La clé doit faire de 8 à 500 caractères visibles, sans espace.";

    /** Jamais la clé dans les journaux ni dans un message d'erreur. */
    @Override
    public String toString() {
        return "CreateProviderRequest[name=" + name + ", baseUrl=" + baseUrl + ", model=" + model
                + ", apiKey=****, supportsJsonMode=" + supportsJsonMode
                + ", dailyRequestLimit=" + dailyRequestLimit + ", enabled=" + enabled + "]";
    }
}
