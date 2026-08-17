package com.yadony.api.matching;

import java.util.EnumSet;
import java.util.Set;

public enum BidStatus {
    AWAITING_PAYMENT,
    PENDING,
    PAYMENT_ESCROWED,
    ACCEPTED,
    HANDED_OVER,
    IN_TRANSIT,
    ARRIVED,
    REJECTED,
    CANCELLED,
    COMPLETED,
    NO_SHOW,
    PARCEL_REFUSED,
    EXPIRED;

    /**
     * Bids que le voyageur a effectivement acceptés — le statut a dépassé le
     * stade PENDING/PAYMENT_ESCROWED, quelle que soit la suite (remise, transit,
     * arrivée, livraison, no-show, refus du colis). Sert au taux d'acceptation, pour ne
     * pas ignorer les bids acceptés puis livrés (qui ne sont plus en ACCEPTED).
     */
    public static final Set<BidStatus> ACCEPTED_OR_BEYOND = EnumSet.of(
            ACCEPTED, HANDED_OVER, IN_TRANSIT, ARRIVED, COMPLETED, NO_SHOW, PARCEL_REFUSED);

    /**
     * Colis actuellement pris en charge par le voyageur mais pas encore livrés :
     * remis en main (HANDED_OVER), en transit (IN_TRANSIT) ou arrivé à destination
     * en attente de retrait (ARRIVED). Sert au compteur « colis en cours » du cockpit.
     */
    public static final Set<BidStatus> EN_ROUTE = EnumSet.of(HANDED_OVER, IN_TRANSIT, ARRIVED);

    /**
     * Statuts pour lesquels le numéro de téléphone de la contrepartie est
     * communicable : ARRIVED est justement le moment où expéditeur et voyageur
     * coordonnent le retrait, donc le pire moment pour masquer le numéro.
     * Source unique — doit rester identique entre {@code BidService} et
     * {@code ConversationService}, qui la référencent tous deux.
     */
    public static final Set<BidStatus> PHONE_VISIBLE_STATUSES;

    static {
        EnumSet<BidStatus> phoneVisible = EnumSet.of(ACCEPTED, COMPLETED);
        phoneVisible.addAll(EN_ROUTE);
        PHONE_VISIBLE_STATUSES = phoneVisible;
    }

    /**
     * Colis « en vol » pour un trajet : accepté (paiement en séquestre, remise
     * pas encore faite) ou dans l'un des statuts {@link #EN_ROUTE}. Tant qu'au
     * moins un bid du trajet est dans cet ensemble, le trajet ne doit pas
     * repasser en COMPLETED.
     */
    public static final Set<BidStatus> IN_FLIGHT;

    static {
        EnumSet<BidStatus> inFlight = EnumSet.of(ACCEPTED);
        inFlight.addAll(EN_ROUTE);
        IN_FLIGHT = inFlight;
    }
}
