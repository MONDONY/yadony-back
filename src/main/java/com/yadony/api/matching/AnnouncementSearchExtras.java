package com.yadony.api.matching;

import com.yadony.api.payments.cash.PaymentMethod;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Filtres de recherche de trajets ajoutés après la signature historique de
 * {@link AnnouncementService#searchAnnouncements} : regroupés ici pour ne pas allonger
 * une liste de paramètres positionnels déjà longue.
 *
 * @param maxStops       FLUTTER-GD, borne d'escales brute (normalisée par {@link TripStops#searchBound})
 * @param paymentMethods FLUTTER-G0, moyens recherchés (jamais null, vide = pas de filtre)
 */
public record AnnouncementSearchExtras(Integer maxStops, Set<PaymentMethod> paymentMethods) {

    public static final AnnouncementSearchExtras NONE = new AnnouncementSearchExtras(null, Set.of());

    public AnnouncementSearchExtras {
        paymentMethods = paymentMethods == null ? Set.of() : Set.copyOf(paymentMethods);
    }

    /** Fragment de clé de cache, stable quel que soit l'ordre des moyens reçus. */
    public String cacheKey() {
        String methods = paymentMethods.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
        return "stops=" + TripStops.searchBound(maxStops) + "_pm=" + methods;
    }
}
