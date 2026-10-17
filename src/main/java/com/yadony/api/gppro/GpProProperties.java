package com.yadony.api.gppro;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Tâches planifiées du badge GP Pro. Détecté par {@code @ConfigurationPropertiesScan}. */
@ConfigurationProperties(prefix = "yadony.gp-pro")
public record GpProProperties(String reviewCron, String cleanupCron, Boolean schedulerEnabled) {

    public boolean schedulerEnabledOrDefault() {
        return schedulerEnabled == null || schedulerEnabled;
    }
}
