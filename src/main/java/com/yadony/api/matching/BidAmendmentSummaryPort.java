package com.yadony.api.matching;

import java.util.Collection;
import java.util.UUID;

/**
 * Modifications d'un colis accepté, vues depuis {@code matching/} pour {@code BidResponse}. Implémenté dans
 * {@code payments/} ({@code BidAmendmentSummaryAdapter}), même découpage que {@link BidSupplementSummaryPort}.
 */
public interface BidAmendmentSummaryPort {

    /** Aucune modification (port absent, tests unitaires) : la réponse garde ses valeurs neutres. */
    BidAmendmentSummaryPort NONE = new BidAmendmentSummaryPort() {
        @Override
        public Prefetch prefetch(Collection<BidEntity> bids) {
            return null;
        }

        @Override
        public BidAmendmentView view(BidEntity bid, AnnouncementEntity announcement, UUID callerId, Prefetch prefetch) {
            return BidAmendmentView.NONE;
        }
    };

    /** Modifications d'une liste de colis (et ce qu'il faut pour {@code amendmentAllowed}), chargées par lots. */
    Prefetch prefetch(Collection<BidEntity> bids);

    /**
     * Vue des modifications pour l'appelant : celles à afficher (visibilité déjà appliquée), le droit de modifier
     * (expéditeur seulement), les kilos acquis et les transactions de la ventilation de l'argent.
     *
     * @param prefetch lectures groupées d'une liste, ou null pour un colis isolé.
     */
    BidAmendmentView view(BidEntity bid, AnnouncementEntity announcement, UUID callerId, Prefetch prefetch);

    /** Lectures groupées, opaques pour {@code matching/}. */
    interface Prefetch {
    }
}
