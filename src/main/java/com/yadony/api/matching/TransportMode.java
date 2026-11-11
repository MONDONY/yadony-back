package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;

import java.util.Locale;

public enum TransportMode {
    PLANE,
    CAR,
    TRAIN,
    BUS,
    BOAT,
    OTHER;

    /**
     * Une demande de colis qui fixe un mode n'est servie que par un trajet de ce mode.
     *
     * <p>{@code requested == null} signifie « peu importe » : tout trajet convient. Voiture et bus
     * sont interchangeables (même route, même durée d'arrivée) ; tous les autres modes exigent
     * l'égalité stricte.
     */
    public static boolean accepts(TransportMode requested, TransportMode offered) {
        if (requested == null) {
            return true;
        }
        if (requested == offered) {
            return true;
        }
        return isRoad(requested) && isRoad(offered);
    }

    /**
     * Mode lu dans un champ texte (modèle de trajet, récurrence) : espaces et casse tolérés,
     * valeur inconnue refusée en 422 {@code invalid-transport-mode} plutôt que stockée telle quelle.
     */
    public static TransportMode parse(String raw) {
        TransportMode mode = parseOrNull(raw);
        if (mode == null) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-transport-mode",
                    "Mode de transport invalide", "Ce mode de transport n'existe pas.");
        }
        return mode;
    }

    /** Variante indulgente pour une valeur déjà stockée, éventuellement héritée d'avant la validation. */
    public static TransportMode parseOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isRoad(TransportMode mode) {
        return mode == CAR || mode == BUS;
    }
}
