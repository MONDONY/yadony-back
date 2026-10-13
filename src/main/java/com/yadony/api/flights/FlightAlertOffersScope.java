package com.yadony.api.flights;

import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;

import java.util.Locale;

/**
 * Portée des vols rendus par {@code GET /me/flight-alerts/{id}/offers} (paramètre {@code scope}).
 *
 * <ul>
 *   <li>{@link #BELOW} ({@code below}, défaut) : seulement les vols au prix inférieur ou égal au seuil ;</li>
 *   <li>{@link #ALL} ({@code all}) : tous les vols de la période de l'alerte, sans filtre de prix.</li>
 * </ul>
 */
enum FlightAlertOffersScope {
    BELOW,
    ALL;

    /**
     * Lit la valeur du paramètre, sans tenir compte de la casse ; absente ou vide, {@link #BELOW}.
     *
     * @throws YadonyBusinessException 400 {@code flights-invalid-scope} pour toute autre valeur
     */
    static FlightAlertOffersScope parse(String raw) {
        if (raw == null || raw.isBlank()) return BELOW;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "below" -> BELOW;
            case "all" -> ALL;
            default -> throw new YadonyBusinessException(HttpStatus.BAD_REQUEST, "flights-invalid-scope",
                    "Invalid Flight Alert Scope", "Valeur de scope inconnue : utilisez below ou all.");
        };
    }
}
