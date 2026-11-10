package com.yadony.api.travelerstatus;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Contrôle quotidien des particuliers fréquents (lot 4). Détecté par {@code @ConfigurationPropertiesScan}. */
@ConfigurationProperties(prefix = "yadony.individual-frequency")
public record IndividualFrequencyProperties(String cron, Boolean schedulerEnabled) {

    public boolean schedulerEnabledOrDefault() {
        return schedulerEnabled == null || schedulerEnabled;
    }
}
