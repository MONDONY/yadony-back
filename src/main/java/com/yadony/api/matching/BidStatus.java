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
    EXPIRED,
    /** Fil de négociation ouvert par l'expéditeur sur un trajet négociable.
     *  Ce n'est PAS une réservation : ce statut doit rester invisible de toutes
     *  les listes, compteurs et statistiques de colis. */
    NEGOTIATING,

    /**
     * Fil de négociation éteint sans accord — refusé, retiré, ou périmé.
     *
     * <p>Statut à part entière plutôt que REJECTED / CANCELLED réutilisés, parce
     * qu'un fil clos n'a JAMAIS été une réservation : le recycler dans un statut
     * de colis le faisait réapparaître partout sous les traits d'une demande de
     * colis refusée — dans « Mes envois », dans la liste voyageur du trajet, et
     * jusque dans le dénominateur du taux d'acceptation du voyageur, qu'un simple
     * refus de prix par l'expéditeur suffisait alors à dégrader.
     *
     * <p>Aucun ensemble d'exclusion ne pouvait porter cette distinction : une fois
     * le fil en REJECTED, plus rien dans le statut ne le séparait d'un vrai refus.
     * Cf. {@link #NEGOTIATION_STATUSES}.
     */
    NEGOTIATION_CLOSED;

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

    /**
     * Fils de négociation encore ouverts. Source unique du filtre d'invisibilité :
     * toute liste destinée au voyageur ou à l'expéditeur doit exclure cet ensemble,
     * un bid en négociation n'étant pas un colis réservé.
     */
    public static final Set<BidStatus> NEGOTIATION_ACTIVE = EnumSet.of(NEGOTIATING);

    /**
     * Le bid est une DISCUSSION DE PRIX, ouverte ou éteinte — jamais un colis.
     *
     * <p>Contrairement à {@link #NEGOTIATION_ACTIVE}, cet ensemble survit à la
     * fermeture du fil : c'est lui qui porte « ce bid n'a jamais engagé personne »
     * une fois la discussion terminée.
     */
    public static final Set<BidStatus> NEGOTIATION_STATUSES =
            EnumSet.of(NEGOTIATING, NEGOTIATION_CLOSED);
}
