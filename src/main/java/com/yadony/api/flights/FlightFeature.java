package com.yadony.api.flights;

import com.yadony.api.config.PlatformSettingsService;
import org.springframework.stereotype.Component;

/**
 * Feature flag des billets d'avion ({@code flights_enabled}, piloté depuis le back-office).
 *
 * <p>Éteint, toutes les routes du package répondent 503 {@code flights-unavailable} (la
 * même réponse que sans token Travelpayouts : l'app sait déjà l'afficher) et le scheduler
 * des alertes ne fait rien. Rien n'est supprimé : rallumer le flag rend les alertes telles
 * qu'elles étaient.
 */
@Component
public class FlightFeature {

    private final PlatformSettingsService settings;

    public FlightFeature(PlatformSettingsService settings) {
        this.settings = settings;
    }

    public boolean isEnabled() {
        return settings.flightsEnabled();
    }

    /** @throws com.yadony.api.common.YadonyBusinessException 503 si le flag est éteint */
    public void requireEnabled() {
        if (!isEnabled()) throw FlightErrors.unavailable();
    }
}
