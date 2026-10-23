package com.yadony.api.payments.amendment.events;

import com.yadony.api.payments.amendment.BidAmendmentClosedReason;
import com.yadony.api.payments.amendment.BidAmendmentStatus;
import com.yadony.api.payments.supplement.BidSupplementRail;

import java.util.UUID;

/**
 * Modification close sans aboutir (refus, annulation, expiration, non apportée). Les rails écoutent ce
 * type commun pour défaire ce qui reste ouvert chez eux ; {@code previousStatus} dit d'où elle vient.
 * Les kilos réservés sont déjà rendus quand l'événement part.
 */
public sealed interface BidAmendmentClosedEvent {

    UUID amendmentId();

    UUID bidId();

    UUID senderId();

    UUID travelerId();

    BidSupplementRail rail();

    BidAmendmentStatus previousStatus();

    BidAmendmentClosedReason closedReason();

    /** Refusée par le voyageur. */
    record Declined(UUID amendmentId, UUID bidId, UUID senderId, UUID travelerId, BidSupplementRail rail,
                    BidAmendmentStatus previousStatus, BidAmendmentClosedReason closedReason)
            implements BidAmendmentClosedEvent {
    }

    /** Annulée : par l'expéditeur, autorisation ou commission jamais confirmée, capture échouée, colis terminé. */
    record Cancelled(UUID amendmentId, UUID bidId, UUID senderId, UUID travelerId, BidSupplementRail rail,
                     BidAmendmentStatus previousStatus, BidAmendmentClosedReason closedReason)
            implements BidAmendmentClosedEvent {
    }

    /** Échue sans réponse du voyageur, ou sans paiement de l'expéditeur. */
    record Expired(UUID amendmentId, UUID bidId, UUID senderId, UUID travelerId, BidSupplementRail rail,
                   BidAmendmentStatus previousStatus, BidAmendmentClosedReason closedReason)
            implements BidAmendmentClosedEvent {
    }

    /** Acceptée, mais l'expéditeur n'a pas apporté ce qu'il avait ajouté à la remise. */
    record NotBrought(UUID amendmentId, UUID bidId, UUID senderId, UUID travelerId, BidSupplementRail rail,
                      BidAmendmentStatus previousStatus, BidAmendmentClosedReason closedReason)
            implements BidAmendmentClosedEvent {
    }
}
