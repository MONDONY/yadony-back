package com.yadony.api.config;

import org.mockito.quality.Strictness;

import java.math.BigDecimal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Fabrique de {@link PlatformSettingsService} pour les tests unitaires.
 *
 * <p>Depuis que les reglages plateforme pilotent le comportement reel et plus seulement
 * l'affichage, les classes qui lisaient {@code YadonyConfigProperties} lisent ce service.
 * Les tests unitaires ont besoin d'une instance qui reponde des valeurs fixes, sans base.
 *
 * <p>Les stubs sont volontairement <b>lenient</b> : chaque appelant n'utilise qu'une partie
 * des quatre reglages, et la strictness de Mockito ferait echouer les autres pour
 * « stubbing inutile » — un echec qui ne dirait rien du comportement teste.
 */
public final class PlatformSettingsTestFactory {

    private PlatformSettingsTestFactory() {
    }

    /** Valeurs par defaut du projet : 5 %, 3 jours, 50 EUR, SMS coupes. */
    public static PlatformSettingsService defaults() {
        return with(new BigDecimal("0.05"), 3, new BigDecimal("50"), false);
    }

    public static PlatformSettingsService withCommissionRate(BigDecimal rate) {
        return with(rate, 3, new BigDecimal("50"), false);
    }

    public static PlatformSettingsService withUrgencyThresholdDays(int days) {
        return with(new BigDecimal("0.05"), days, new BigDecimal("50"), false);
    }

    public static PlatformSettingsService withSmsEnabled(boolean enabled) {
        return with(new BigDecimal("0.05"), 3, new BigDecimal("50"), enabled);
    }

    public static PlatformSettingsService with(BigDecimal commissionRate,
                                               int urgencyThresholdDays,
                                               BigDecimal reimbursementCapEur,
                                               boolean smsEnabled) {
        PlatformSettingsService settings =
                mock(PlatformSettingsService.class, withSettings().strictness(Strictness.LENIENT));
        when(settings.commissionRate()).thenReturn(commissionRate);
        when(settings.urgencyThresholdDays()).thenReturn(urgencyThresholdDays);
        when(settings.reimbursementCapEur()).thenReturn(reimbursementCapEur);
        when(settings.smsEnabled()).thenReturn(smsEnabled);
        return settings;
    }
}
