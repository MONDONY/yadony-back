package com.yadony.api.dac7;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Rappels DAC7 (lot 3b), ÉTEINTS par défaut ({@code YADONY_DAC7_REMINDERS_ENABLED}). Même allumés, ils ne partent que si
 * la collecte l'est aussi ({@code yadony.dac7.enabled}) : l'écran de saisie n'existe pas sinon. Demande initiale dès
 * que le voyageur devient « à solliciter », rappel 1 à {@code firstReminderDelay} de la demande, rappel 2 à
 * {@code secondReminderDelay}, jamais de troisième. Détecté par {@code @ConfigurationPropertiesScan}.
 *
 * @param manualMinInterval      délai minimal entre deux messages quand l'équipe relance à la main
 * @param minGapBetweenReminders écart minimal entre le rappel 1 et le rappel 2, même après une pause du planificateur
 */
@ConfigurationProperties(prefix = "yadony.dac7.reminders")
public record Dac7ReminderProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("P14D") Duration firstReminderDelay,
        @DefaultValue("P30D") Duration secondReminderDelay,
        @DefaultValue("P7D") Duration manualMinInterval,
        @DefaultValue("P7D") Duration minGapBetweenReminders,
        @DefaultValue("10") int windowStartHour,
        @DefaultValue("20") int windowEndHour) {

    public Dac7ReminderProperties {
        if (firstReminderDelay.isNegative() || firstReminderDelay.isZero()
                || secondReminderDelay.compareTo(firstReminderDelay) <= 0) {
            throw new IllegalArgumentException("yadony.dac7.reminders : délais positifs et croissants attendus");
        }
        if (windowStartHour < 0 || windowEndHour > 24 || windowStartHour >= windowEndHour) {
            throw new IllegalArgumentException("yadony.dac7.reminders : fenêtre horaire invalide");
        }
    }

    /** Valeurs par défaut, pour les tests sans Spring. */
    public static Dac7ReminderProperties defaults(boolean enabled) {
        return new Dac7ReminderProperties(enabled, Duration.ofDays(14), Duration.ofDays(30), Duration.ofDays(7),
                Duration.ofDays(7), 10, 20);
    }
}
