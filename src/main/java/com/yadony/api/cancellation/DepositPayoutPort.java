package com.yadony.api.cancellation;

import java.util.UUID;

/**
 * L'argent de la plateforme est-il déjà parti vers le transporteur au dépôt d'un colis payé au dépôt
 * ({@code bids.payout_trigger = DEPOSIT}) ? Question posée par les annulations, le refus du colis et l'annulation du
 * trajet AVANT de rembourser l'expéditeur : après un versement au dépôt, aucun remboursement automatique n'est promis
 * (409 {@code deposit-paid-no-refund}, le support ouvre un litige ; décision du propriétaire du 11/11).
 *
 * <p>Le paquet {@code cancellation} décide ; le paquet {@code payments}, seul à connaître les paiements et les
 * modifications de colis, répond. Port plutôt qu'événement : la réponse est nécessaire AVANT d'annuler, ce qu'un
 * écouteur asynchrone ne peut pas donner (même motif que {@link PrePaymentReleasePort}).
 *
 * <p><b>Contrat de verrou</b> : appelé dans la transaction de l'appelant, APRÈS le verrou du colis (ordre colis puis
 * paiement, celui du versement) ; l'implémentation attend un versement concurrent en cours puis relit le statut en base.
 */
@FunctionalInterface
public interface DepositPayoutPort {

    /**
     * Repli si aucune implémentation n'est branchée : on suppose le transporteur payé. Un colis payé au dépôt et
     * déposé n'est alors jamais remboursé automatiquement (le support tranche) ; les autres colis ne sont pas concernés.
     */
    DepositPayoutPort ASSUME_PAID = bidId -> true;

    /**
     * @return vrai si un paiement de la plateforme a réellement été versé (ou est en cours de versement) au transporteur
     *         pour ce colis : principal carte ou mobile money libéré, ou modification versée. Jamais sur la seule règle
     *         figée, jamais pour un principal en espèces (aucun argent plateforme).
     */
    boolean paidAtDeposit(UUID bidId);
}
