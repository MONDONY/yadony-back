package com.yadony.api.matching;

import java.util.Collection;
import java.util.UUID;

/**
 * Supplément de prix d'un colis, vu depuis {@code matching/} pour {@code BidResponse} et le refus
 * du colis. Implémenté dans {@code payments/} ({@code BidSupplementSummaryAdapter}), même découpage
 * que {@link BidNegotiationMobileMoneyPort}.
 */
public interface BidSupplementSummaryPort {

    /** Aucun supplément (port absent, tests unitaires) : la réponse garde ses valeurs neutres. */
    BidSupplementSummaryPort NONE = new BidSupplementSummaryPort() {
        @Override
        public Prefetch prefetch(Collection<UUID> bidIds) {
            return null;
        }

        @Override
        public BidSupplementView view(BidEntity bid, AnnouncementEntity announcement, UUID callerId, Prefetch prefetch) {
            return BidSupplementView.NONE;
        }

        @Override
        public boolean excusesParcelRefusal(UUID bidId) {
            return false;
        }
    };

    /** Suppléments d'une liste de colis, chargés en une requête par lot ; à repasser à {@link #view}. */
    Prefetch prefetch(Collection<UUID> bidIds);

    /**
     * Vue du supplément pour l'appelant : dernier supplément du colis (l'actif, sinon le dernier
     * clos), droit de demander, montants payés à ajouter aux totaux.
     *
     * @param prefetch lectures groupées d'une liste, ou null pour un colis isolé.
     */
    BidSupplementView view(BidEntity bid, AnnouncementEntity announcement, UUID callerId, Prefetch prefetch);

    /**
     * Le dernier supplément du colis a-t-il été refusé par l'expéditeur, laissé expirer, ou attend-il
     * encore sa réponse ? Le refus du colis n'est alors pas compté à l'expéditeur (décisions du
     * propriétaire du 19/10/2026 : pas de suspension pour un colis refusé autour d'un supplément).
     */
    boolean excusesParcelRefusal(UUID bidId);

    /** Lectures groupées, opaques pour {@code matching/}. */
    interface Prefetch {
    }
}
