package com.yadony.api.gppro;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Tâches planifiées du badge GP Pro. Détecté par {@code @ConfigurationPropertiesScan}. */
@ConfigurationProperties(prefix = "yadony.gp-pro")
public record GpProProperties(String reviewCron, String cleanupCron, Boolean schedulerEnabled) {

    public String reviewCronOrDefault() {
        return reviewCron != null && !reviewCron.isBlank() ? reviewCron : "0 15 4 * * *";
    }

    public String cleanupCronOrDefault() {
        return cleanupCron != null && !cleanupCron.isBlank() ? cleanupCron : "0 45 4 * * *";
    }

    public boolean schedulerEnabledOrDefault() {
        return schedulerEnabled == null || schedulerEnabled;
    }
}
