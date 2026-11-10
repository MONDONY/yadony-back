package com.yadony.api.travelerstatus;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Contrôle quotidien des particuliers fréquents (lot 4). Détecté par {@code @ConfigurationPropertiesScan}.
 * Éteint sauf {@code scheduler-enabled: true} explicite ({@code YADONY_INDIVIDUAL_FREQUENCY_SCHEDULER_ENABLED}).
 */
@ConfigurationProperties(prefix = "yadony.individual-frequency")
public record IndividualFrequencyProperties(String cron, Boolean schedulerEnabled) {

    public boolean schedulerEnabledOrDefault() {
        return Boolean.TRUE.equals(schedulerEnabled);
    }
}
