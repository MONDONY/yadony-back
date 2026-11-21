package com.yadony.api.payments.fee;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Frais d'annulation dus par un expéditeur fautif, vus du paquet {@code payments}.
 *
 * <p>Le paquet {@code cancellation} décide qu'une retenue est due (ligne {@code DUE} écrite dans la
 * transaction de l'annulation) ; le paquet {@code payments}, seul à connaître le montant payé, les
 * frais du prestataire et la commission, la calcule et la règle au remboursement. Port plutôt
 * qu'événement : le remboursement a besoin de la réponse AVANT de rembourser. Le paquet
 * {@code payments} possède l'interface, {@code cancellation} l'implémente (inversion de dépendance,
 * même motif que {@code cancellation.DepositPayoutPort} dans l'autre sens).
 */
public interface CancellationFeeRatePort {

    /**
     * Lecture sans verrou : l'appelant la fait sous une garantie qui lui est propre (le claim du paiement
     * pour un remboursement, voir {@code RefundProcessor}).
     *
     * @return le taux de la retenue (0 &lt; taux &le; 1) si une retenue est due et pas encore réglée pour
     *         ce colis ; vide sinon (aucune faute de l'expéditeur, espèces, retenue déjà réglée).
     */
    Optional<BigDecimal> dueRate(UUID bidId);

    /** Plafond du dédommagement par colis, en euros (barème du paquet {@code cancellation}). */
    BigDecimal capEur();

    /**
     * Enregistre la ventilation de la retenue réglée et passe la ligne en {@code RETAINED}. Idempotent :
     * sans effet si la ligne n'est plus due (déjà réglée) ou n'existe pas.
     *
     * @param currency devise du paiement remboursé (code ISO à 3 lettres)
     */
    void markRetained(UUID bidId, CancellationFeeBreakdown breakdown, String currency);

    /**
     * Clôt en {@code NOT_APPLICABLE} une retenue encore due que le remboursement n'a pas pu porter
     * (l'expéditeur a été remboursé en entier). Idempotent : sans effet si la ligne n'est plus due ou
     * n'existe pas.
     *
     * @param reason motif court, repris dans l'audit
     */
    void markNotApplicable(UUID bidId, String reason);
}
