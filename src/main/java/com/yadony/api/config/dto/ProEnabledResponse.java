package com.yadony.api.config.dto;

/**
 * Forme publique du feature flag PRO, alignee sur {@link SmsEnabledResponse} : l'application
 * mobile lit les deux avec le meme parseur ({@code data['enabled']}).
 */
public record ProEnabledResponse(boolean enabled) {}
