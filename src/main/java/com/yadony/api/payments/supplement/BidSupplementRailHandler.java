package com.yadony.api.payments.supplement;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.payments.supplement.dto.SupplementAcceptResponse;

/**
 * Rail de paiement d'un supplément (carte, mobile money, espèces), livré rail par rail. Tant
 * qu'aucun composant n'existe pour le rail d'un colis, la demande est impossible
 * ({@code supplementRequestAllowed} faux, 422 {@code supplement-rail-unsupported}).
 */
public interface BidSupplementRailHandler {

    BidSupplementRail rail();

    /**
     * Fin de la création, dans la transaction et sous le verrou du colis : le rail espèces y
     * prélève la commission du supplément au voyageur et peut laisser la demande en
     * {@code COMMISSION_PENDING}. Par défaut, rien : la demande reste {@code PROPOSED}.
     */
    default void onCreated(BidSupplementEntity supplement) {
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
