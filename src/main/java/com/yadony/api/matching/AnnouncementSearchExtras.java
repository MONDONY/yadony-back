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
 * @param includeFull    FLUTTER-JH, ajoute les trajets complets à venir APRÈS les trajets
 *                       réservables. Absent (ancienne app, compteur) : réservables seuls.
 */
public record AnnouncementSearchExtras(Integer maxStops, Set<PaymentMethod> paymentMethods, boolean includeFull) {

    public static final AnnouncementSearchExtras NONE = new AnnouncementSearchExtras(null, Set.of(), false);

    public AnnouncementSearchExtras {
        paymentMethods = paymentMethods == null ? Set.of() : Set.copyOf(paymentMethods);
    }

    public AnnouncementSearchExtras(Integer maxStops, Set<PaymentMethod> paymentMethods) {
        this(maxStops, paymentMethods, false);
    }

    /** Fragment de clé de cache, stable quel que soit l'ordre des moyens reçus. */
    public String cacheKey() {
        String methods = paymentMethods.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
        return "stops=" + TripStops.searchBound(maxStops) + "_pm=" + methods + "_full=" + includeFull;
    }
}
