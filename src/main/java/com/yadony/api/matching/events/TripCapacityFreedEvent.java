package com.yadony.api.matching.events;

import java.util.UUID;

/**
 * Un trajet complet ({@code FULL}) redevient réservable ({@code ACTIVE}) : un colis a été
 * annulé, refusé ou retiré, ou la capacité est revenue d'une autre façon (report, décision
 * de report, annulation admin).
 *
 * <p>Publié par {@link com.yadony.api.matching.AnnouncementStatusTransitionListener} au
 * flush de l'annonce, quel que soit le chemin qui a rouvert la place : aucune des
 * transitions {@code FULL → ACTIVE} présentes ou futures n'a à penser à le publier.
 * Les écouteurs doivent être {@code AFTER_COMMIT} : l'événement naît pendant le flush.
 */
public record TripCapacityFreedEvent(UUID announcementId, UUID travelerId) {}
