package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;

/**
 * Nombre d'escales d'un trajet (FLUTTER-GE / FLUTTER-GD), information facultative.
 *
 * <p>Valeurs : 0 = direct, 1 = une escale, 2 = deux escales ou plus ({@link #TWO_OR_MORE}).
 * Null = non renseigné. Seul l'avion porte cette information : en voiture, bus, train ou
 * bateau, la notion d'escale n'a pas de sens pour l'expéditeur (un arrêt ne change rien
 * au colis), la valeur est donc effacée à l'écriture ({@link #normalize}).
 */
public final class TripStops {

    /** Plafond : « 2 escales ou plus ». Aligné sur le CHECK de V307. */
    public static final int TWO_OR_MORE = 2;

    private TripStops() {}

    /**
     * Valide puis normalise la valeur saisie : 422 hors de 0..2, null hors avion.
     *
     * @return la valeur à enregistrer
     */
    public static Integer normalize(Integer stopsCount, TransportMode transportMode) {
        if (stopsCount == null) {
            return null;
        }
        if (stopsCount < 0 || stopsCount > TWO_OR_MORE) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "invalid-stops-count", "Nombre d'escales invalide",
                    "Le nombre d'escales doit valoir 0 (direct), 1 ou 2 (deux escales ou plus)");
        }
        return transportMode == TransportMode.PLANE ? stopsCount : null;
    }

    /**
     * Valeur à garder lors d'une modification : un client qui n'envoie pas le champ (version
     * antérieure de l'app) conserve la valeur enregistrée, sauf si le trajet n'est plus en avion.
     */
    public static Integer normalizeOnUpdate(Integer requested, Integer current, TransportMode transportMode) {
        Integer candidate = requested != null ? requested : current;
        return normalize(candidate, transportMode);
    }

    /**
     * Borne de recherche « escales maximum » lue depuis la requête. Absente, négative ou au
     * moins {@link #TWO_OR_MORE} : aucun filtre (« peu importe »).
     */
    public static Integer searchBound(Integer maxStops) {
        if (maxStops == null || maxStops < 0 || maxStops >= TWO_OR_MORE) {
            return null;
        }
        return maxStops;
    }
}
