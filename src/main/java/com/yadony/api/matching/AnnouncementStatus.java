package com.yadony.api.matching;

import java.util.EnumSet;
import java.util.Set;

public enum AnnouncementStatus {
    DRAFT,
    ACTIVE,
    FULL,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
    /** Retirée par un administrateur (modération). Restaurable vers ACTIVE. */
    REMOVED_BY_ADMIN;

    /**
     * Lot B (revue round 3) — statuts qui signifient « cette annonce est définitivement
     * sortie du marché » : plus aucun paiement/escrow ne doit pouvoir s'y engager. Ne
     * contient délibérément PAS {@code FULL} : un trajet partagé peut légitimement passer
     * FULL entre l'attachement d'un fil de négociation et son paiement (un autre expéditeur
     * a rempli la capacité entretemps) — bloquer ce paiement serait une régression sur un
     * chemin qui fonctionnait, pour un sujet de sur-booking préexistant et hors périmètre.
     * Utilisé par les gardes posées avant un engagement d'argent sur un trajet déjà attaché
     * (négociation de demande de colis et négociation de prix sur bid) — pas par la garde de
     * PREMIÈRE entrée sur un trajet ({@code BidCheckoutService.checkout},
     * {@code BidService.assertCanBidOn}), qui reste sur l'allowlist {@code == ACTIVE}
     * puisqu'un nouvel entrant ne doit pas pouvoir viser un trajet FULL.
     */
    public static final Set<AnnouncementStatus> OUT_OF_MARKET =
            EnumSet.of(REMOVED_BY_ADMIN, CANCELLED, COMPLETED);
}
