package com.yadony.api.cancellation.events;

import java.util.UUID;

/**
 * Publié par {@code BidLostRematchListener} après qu'une {@code CancellationEntity} et
 * d'éventuelles suggestions rematch aient été créées suite à un {@code BidRejectedEvent}
 * éligible (annulation/refus voyageur d'un bid payé, ou suppression de son propre trajet
 * par le voyageur — hors annulation de trajet via {@code cancelTrip}).
 *
 * <p>Consommé par {@code NotificationDispatcher} pour envoyer une notification unique
 * enrichie (deep link rematch si {@code suggestionCount > 0}) et sauter la notification
 * {@code BID_REJECTED} générique.
 *
 * @param reason motif brut du {@code BidRejectedEvent} d'origine (revue round 3) — permet à
 *               {@code NotificationDispatcher} de distinguer, au sein des motifs « initiés
 *               par le voyageur », une suppression de trajet
 *               ({@code BidEntity.REJECTION_ANNOUNCEMENT_DELETED}) d'une annulation/refus de
 *               bid explicite, dont les libellés diffèrent.
 */
public record BidLostRematchPreparedEvent(
        UUID senderId,
        UUID bidId,
        UUID cancellationId,
        int suggestionCount,
        boolean cancelledByTraveler,
        String reason) {
}
