package com.yadony.api.common;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;

/**
 * Le lecteur de la requête courante est-il une session Firebase anonyme.
 *
 * <p>On teste l'autorité {@code ROLE_GUEST} posée par {@code FirebaseTokenFilter} plutôt que
 * de relire le claim du token : c'est le filtre qui fait autorité sur « cette session est-elle
 * anonyme ? », le relire ailleurs en ferait une seconde source de vérité. Voir
 * {@link FirebaseSignInProvider}, que le filtre consulte en amont pour poser cette autorité.
 *
 * <p>Extrait ici parce que la question se posait déjà à trois endroits ({@code FavoriteController},
 * {@code PackageRequestController}, et désormais les mappers de prix) avec trois copies de la
 * même boucle. Une seule définition évite qu'elles divergent.
 */
public final class GuestSession {

    /** Autorité posée en dur par le filtre pour une session anonyme. Jamais un rôle en base. */
    public static final String ROLE_GUEST = "ROLE_GUEST";

    private GuestSession() {
    }

    /**
     * Vrai uniquement si la requête courante est portée par une session anonyme.
     *
     * <p>Null-safe : hors contexte de requête (ordonnanceur, écouteur d'événement, test
     * unitaire), il n'y a pas d'authentification et la réponse est {@code false}. Le repli
     * « non invité » est le bon : il laisse le comportement historique inchangé partout où la
     * notion n'a pas de sens.
     */
    public static boolean isGuest() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return false;
        }
        return auth.getAuthorities().stream().anyMatch(a -> ROLE_GUEST.equals(a.getAuthority()));
    }

    /**
     * Le net voyageur tel qu'il doit être servi au lecteur courant : la valeur pour un compte
     * inscrit, {@code null} pour un invité.
     *
     * <p>Décision produit du 2026-08-22. Le net voyageur est ce que touche le transporteur ;
     * le brut ({@code pricePerKgDisplay}, {@code unitPriceDisplay}) est ce que paie
     * l'expéditeur, et c'est lui la seule information utile à un visiteur. Trois raisons de ne
     * pas servir le net à une session anonyme :
     *
     * <ul>
     *   <li>Net et brut voyagent <b>côte à côte dans le même objet JSON</b> : il n'y a aucune
     *       déduction à faire, c'est une lecture directe.</li>
     *   <li>Le taux de commission effectif n'est pas toujours public.
     *       {@link CommissionRateResolver} applique un {@code commissionRateOverride} porté par
     *       le voyageur et jamais exposé : pour un voyageur à taux négocié, le couple net/brut
     *       révèle une donnée commerciale privée.</li>
     *   <li>Le projet a déjà tranché la même question sur une surface comparable :
     *       {@code PublicAnnouncementPageController} est en {@code permitAll}, donc plus ouvert
     *       qu'un invité, et ne sert jamais le net. Une session anonyme, gratuite et
     *       automatisable en masse, est plus proche de ce cas que d'un compte inscrit.</li>
     * </ul>
     *
     * <p>La sérialisation étant en {@code NON_NULL}, la clé disparaît entièrement de la charge
     * plutôt que d'apparaître à {@code null}.
     */
    public static BigDecimal travelerNetOrNull(BigDecimal net) {
        return isGuest() ? null : net;
    }
}
