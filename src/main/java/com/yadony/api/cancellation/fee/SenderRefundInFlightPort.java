package com.yadony.api.cancellation.fee;

import java.util.UUID;

/**
 * Le remboursement de l'expéditeur d'un colis est-il déjà parti, ou en route, chez le prestataire ? Question posée
 * par la renonciation à une retenue ({@link CancellationFeeAdminService#waive}) : renoncer pendant qu'un remboursement
 * PARTIEL (le dépôt moins la retenue) est vivant laisserait l'expéditeur remboursé d'une partie seulement, sans
 * dédommagement pour le voyageur.
 *
 * <p>Le paquet {@code cancellation} décide ; le paquet {@code payments}, seul à connaître les paiements et les
 * opérations du prestataire, répond (même inversion que {@code cancellation.DepositPayoutPort}). Port plutôt
 * qu'événement : la réponse est nécessaire AVANT de clore la retenue.
 *
 * <p><b>Contrat de verrou</b> : appelé sous le verrou de la ligne de la retenue ; l'implémentation ne fait que des
 * lectures simples, jamais un verrou sur le paiement (ordre des verrous du remboursement : paiement puis retenue ;
 * la renonciation ne prend que la retenue, aucun cycle possible).
 */
@FunctionalInterface
public interface SenderRefundInFlightPort {

    /**
     * @return vrai si un remboursement du paiement de ce colis est vivant ou abouti chez le prestataire, sans que la
     *         retenue ait été réglée. Rail carte : toujours faux (le remboursement Stripe est émis et constaté dans la
     *         même transaction que le règlement, il n'y a pas de remboursement « en vol »).
     */
    boolean senderRefundInFlight(UUID bidId);
}
