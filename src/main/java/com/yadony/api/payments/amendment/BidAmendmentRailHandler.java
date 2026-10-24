package com.yadony.api.payments.amendment;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.payments.amendment.dto.AmendmentAcceptRequest;
import com.yadony.api.payments.amendment.dto.AmendmentAcceptResponse;
import com.yadony.api.payments.amendment.dto.AmendmentCreateResponse;
import com.yadony.api.payments.supplement.BidSupplementRail;

/**
 * Rail de paiement d'une modification (carte, mobile money, espèces), livré rail par rail. Un rail sans
 * composant n'est jamais proposé : la création y est refusée en 422 {@code amendment-rail-unavailable}.
 *
 * <p>Les méthodes sans suffixe {@code Commit} s'exécutent dans la transaction du service, sous les verrous
 * du colis, du trajet et de la modification : aucun appel prestataire. Les suites {@code after…Commit}
 * s'exécutent APRÈS le commit, hors transaction ({@code BidAmendmentPaymentFlow}).
 */
public interface BidAmendmentRailHandler {

    /** Statut d'une modification tout juste créée : en attente d'autorisation (carte) ou proposée au voyageur. */
    record CreateOutcome(BidAmendmentStatus status) {

        public static final CreateOutcome PROPOSED = new CreateOutcome(BidAmendmentStatus.PROPOSED);
        public static final CreateOutcome AUTHORIZATION_PENDING =
                new CreateOutcome(BidAmendmentStatus.AUTHORIZATION_PENDING);

        public CreateOutcome {
            if (status != BidAmendmentStatus.PROPOSED && status != BidAmendmentStatus.AUTHORIZATION_PENDING) {
                throw new IllegalArgumentException("Statut initial d'une modification invalide : " + status);
            }
        }
    }

    BidSupplementRail rail();

    /**
     * Fin de la création, modification enregistrée et kilos réservés. Rend le statut initial : {@code PROPOSED}
     * par défaut (rien à payer avant la réponse du voyageur) ; la carte rend {@code AUTHORIZATION_PENDING}
     * (le montant est bloqué d'abord). Une exception annule la création.
     */
    default CreateOutcome onCreated(BidAmendmentEntity amendment, UserEntity sender) {
        return CreateOutcome.PROPOSED;
    }

    /** Suite de la création, après son commit : la carte y crée le PaymentIntent. Par défaut, la réponse telle quelle. */
    default AmendmentCreateResponse afterCreateCommit(AmendmentCreateResponse created) {
        return created;
    }

    /**
     * Acceptation par le voyageur d'une modification {@code PROPOSED} non échue. Le rail la rend acquise
     * ({@link BidAmendmentService#markAccepted}) ou la fait passer à son étape de paiement.
     */
    AmendmentAcceptResponse accept(BidAmendmentEntity amendment, UserEntity traveler, AmendmentAcceptRequest request);

    /** Suite de l'acceptation, après son commit : la carte y capture. Par défaut, la réponse telle quelle. */
    default AmendmentAcceptResponse afterAcceptCommit(AmendmentAcceptResponse accepted) {
        return accepted;
    }

    /**
     * La modification vient d'être close sans aboutir (refus, annulation, expiration), kilos déjà rendus, DANS la
     * transaction qui la clôt (parfois celle du scan de remise, qui peut encore échouer) : aucun appel prestataire
     * ici. Rien par défaut. L'autorisation carte est rendue après le commit, par l'écouteur du rail carte
     * ({@code BidAmendmentCardListener}) ; la commission espèces, par celui du rail espèces.
     */
    default void onClosed(BidAmendmentEntity amendment, BidAmendmentStatus previous) {
    }

    /**
     * Une modification {@code PAYMENT_PENDING} peut-elle être close maintenant (expiration, annulation par
     * l'expéditeur) ? Un dépôt mobile money vivant ne s'annule pas : on attend son issue. Par défaut, oui.
     */
    default boolean mayExpire(BidAmendmentEntity amendment) {
        return true;
    }
}
