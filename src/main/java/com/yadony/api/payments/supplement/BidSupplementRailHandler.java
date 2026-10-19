package com.yadony.api.payments.supplement;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.matching.dto.BidSupplementResponse;
import com.yadony.api.payments.supplement.dto.CreateSupplementRequest;
import com.yadony.api.payments.supplement.dto.SupplementAcceptResponse;

/**
 * Rail de paiement d'un supplément (carte, mobile money, espèces), livré rail par rail. Tant
 * qu'aucun composant n'existe pour le rail d'un colis, la demande est impossible
 * ({@code supplementRequestAllowed} faux, 422 {@code supplement-rail-unsupported}).
 */
public interface BidSupplementRailHandler {

    BidSupplementRail rail();

    /**
     * Le voyageur peut-il demander un supplément sur ce rail, au-delà de {@link BidSupplementRail#payableBy} ?
     * Le rail espèces exige de quoi payer la commission (solde ou carte commission). Sert au bouton
     * de l'app ({@code supplementRequestAllowed}) ; la création fait le contrôle exact. Par défaut, oui.
     */
    default boolean canRequest(UserEntity traveler, String currency) {
        return true;
    }

    /**
     * Fin de la création, dans la transaction et sous le verrou du colis : le rail espèces y
     * prélève la commission du supplément au voyageur (solde), ou laisse la demande en
     * {@code COMMISSION_PENDING} pour la carte, débitée après le commit
     * ({@link #afterCreateCommit}). Une exception annule la création. Par défaut, rien : la
     * demande reste {@code PROPOSED}.
     */
    default void onCreated(BidSupplementEntity supplement, CreateSupplementRequest request) {
    }

    /**
     * Suite de la création, APRÈS son commit et hors de toute transaction : le rail espèces y
     * débite la carte commission du voyageur. Par défaut, la réponse de la création.
     */
    default BidSupplementResponse afterCreateCommit(BidSupplementResponse created) {
        return created;
    }

    /**
     * Acceptation par l'expéditeur d'une demande {@code PROPOSED} ou {@code PAYMENT_PENDING}, non
     * échue, dans la transaction et sous les verrous du colis et de la demande : aucun appel
     * prestataire ici.
     */
    SupplementAcceptResponse accept(BidSupplementEntity supplement, UserEntity sender);

    /**
     * Suite de l'acceptation, APRÈS le commit de {@link #accept} et hors de toute transaction : le
     * rail carte y crée (ou réutilise) le PaymentIntent. Par défaut, la réponse de {@link #accept}.
     */
    default SupplementAcceptResponse afterAcceptCommit(SupplementAcceptResponse prepared) {
        return prepared;
    }

    /**
     * Une demande {@code PAYMENT_PENDING} échue peut-elle être close maintenant ? Un dépôt mobile
     * money ne s'annule pas : on attend son issue tant qu'il est ouvert. Par défaut, oui.
     */
    default boolean mayExpire(BidSupplementEntity supplement) {
        return true;
    }
}
