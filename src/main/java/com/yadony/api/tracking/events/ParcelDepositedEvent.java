package com.yadony.api.tracking.events;

import java.util.UUID;

/**
 * Publié par {@code TrackingService} dans la transaction du scan qui enregistre le DEPART d'un colis
 * payé au dépôt ({@code bids.payout_trigger = DEPOSIT}) et le fait passer de ACCEPTED à HANDED_OVER :
 * le colis vient d'être déposé chez le transporteur. Le paquet {@code payments} l'écoute après le
 * commit pour verser le transporteur ({@code DeliveryEventListener#handleParcelDeposited}).
 *
 * <p>Une seule publication par colis : un DEPART rejoué (scan en double, file hors ligne) renvoie
 * l'étape existante sans rien republier, et un colis payé à la livraison n'en publie jamais.
 */
public record ParcelDepositedEvent(UUID bidId, UUID senderId, UUID travelerId) {}
