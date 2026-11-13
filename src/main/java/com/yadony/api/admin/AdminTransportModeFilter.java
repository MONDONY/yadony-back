package com.yadony.api.admin;

import com.yadony.api.matching.TransportMode;

import java.util.List;
import java.util.Locale;

/**
 * Filtre « mode de transport » des listes admin ({@code ?transportMode=}). Valeur absente ou vide :
 * pas de filtre ({@code null}). {@code ANY} : « peu importe » (une demande sans mode ; pour les
 * annonces, qui ont toujours un mode, pas de filtre). Sinon un nom de mode, voiture et bus formant
 * un seul groupe « Route » comme dans la recherche ({@link TransportMode#accepts}). Valeur inconnue :
 * 422 {@code invalid-transport-mode}.
 *
 * @param any  {@code true} pour {@code ANY}
 * @param mode le mode demandé, {@code null} pour {@code ANY}
 */
public record AdminTransportModeFilter(boolean any, TransportMode mode) {

    static final String ANY = "ANY";

    public static AdminTransportModeFilter parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (ANY.equals(raw.trim().toUpperCase(Locale.ROOT))) {
            return new AdminTransportModeFilter(true, null);
        }
        return new AdminTransportModeFilter(false, TransportMode.parse(raw));
    }

    /** Modes qui satisfont le filtre ; vide pour {@code ANY}. */
    public List<TransportMode> modes() {
        return any ? List.of() : TransportMode.acceptedBy(mode);
    }
}
