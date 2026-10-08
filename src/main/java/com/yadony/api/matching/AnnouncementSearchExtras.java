package com.yadony.api.matching;

/**
 * Filtres de recherche de trajets ajoutés après la signature historique de
 * {@link AnnouncementService#searchAnnouncements} : regroupés ici pour ne pas allonger
 * une liste de paramètres positionnels déjà longue.
 *
 * @param maxStops FLUTTER-GD, borne d'escales brute (normalisée par {@link TripStops#searchBound})
 */
public record AnnouncementSearchExtras(Integer maxStops) {

    public static final AnnouncementSearchExtras NONE = new AnnouncementSearchExtras(null);

    /** Fragment de clé de cache. */
    public String cacheKey() {
        return "stops=" + TripStops.searchBound(maxStops);
    }
}
