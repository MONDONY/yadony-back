package com.yadony.api.matching;

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
    DEPOSIT
}
