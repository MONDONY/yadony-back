package com.yadony.api.address;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "app.places")
@Validated
public record GooglePlacesProperties(
    @NotBlank String apiKey,
    String allowedCountries,
    int rateLimitPerMinute,
    int dailyQuotaAutocomplete,
    int dailyQuotaDetails,
    int dailyQuotaReverse,
    boolean blockWhenQuotaExceeded,
    /** Biais de proximité (cercle 50 km autour de la position) dans l'autocomplétion.
     *  Désactivé par défaut : un biais serré sature les 5 suggestions Google de
     *  résultats locaux et empêche les villes lointaines (ex. Toronto) de remonter. */
    boolean locationBiasEnabled
) {}
