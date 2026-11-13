package com.yadony.api.cancellation.events;

import java.util.List;
import java.util.UUID;

/**
 * Le voyageur a demandé l'annulation d'un trajet qui porte des colis déjà payés au transporteur à leur dépôt :
 * l'annulation est refusée (409 {@code deposit-paid-no-refund}) et l'équipe doit reprendre la main (colis déposés,
 * transporteur payé, expéditeurs à contacter).
 *
 * <p>Publié de façon SYNCHRONE juste avant le refus, dans une transaction qui va être annulée : l'écouteur ne doit
 * écrire que dans une transaction indépendante (l'alerte admin dédupliquée le fait), jamais en
 * {@code AFTER_COMMIT}, qui ne se déclencherait pas.
 *
 * @param announcementId trajet dont l'annulation est refusée
 * @param travelerId     voyageur qui a demandé l'annulation
 * @param paidBidIds     colis déposés et déjà payés au dépôt
 */
public record TripCancelRefusedDepositPaidEvent(UUID announcementId, UUID travelerId, List<UUID> paidBidIds) {

    public TripCancelRefusedDepositPaidEvent {
        paidBidIds = List.copyOf(paidBidIds);
    }
}
