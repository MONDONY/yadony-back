package com.yadony.api.matching;

import java.util.EnumSet;
import java.util.Set;

/**
 * Moment où l'argent d'un colis part vers le transporteur ({@code bids.payout_trigger}, V383).
 *
 * <p>Figé une seule fois, à l'acceptation du colis, par {@link PayoutTriggerPolicy#freeze} : jamais
 * recalculé ensuite, quoi qu'il arrive au trajet, à l'interrupteur ou au profil du transporteur.
 */
public enum PayoutTrigger {

    /** À la livraison confirmée : la règle de toujours, valable pour tous les modes. */
    DELIVERY,

    /**
     * Au dépôt du colis chez le transporteur (scan de départ), sans attendre la livraison : trajet
     * bateau d'un transporteur vérifié, interrupteur bateau allumé au moment de l'acceptation.
     */
    DEPOSIT;

    /**
     * Statuts d'un colis déposé et toujours dans le parcours (seul le scan DEPART fait passer un colis accepté à
     * HANDED_OVER). Définition unique du « déposé », partagée par le versement au dépôt, les gardes d'annulation et la
     * fermeture du supplément de prix.
     */
    public static final Set<BidStatus> DEPOSITED_STATUSES = java.util.Collections.unmodifiableSet(
            EnumSet.of(BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED, BidStatus.COMPLETED));
}
