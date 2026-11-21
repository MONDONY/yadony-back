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
     * @return le taux de la retenue (0 &lt; taux &le; 1) si une retenue est due et pas encore réglée pour
     *         ce colis ; vide sinon (aucune faute de l'expéditeur, espèces, retenue déjà réglée).
     */
    Optional<BigDecimal> dueRate(UUID bidId);

    /**
     * Enregistre la ventilation de la retenue réglée et passe la ligne en {@code RETAINED}. Idempotent :
     * sans effet si la ligne n'est plus due (déjà réglée) ou n'existe pas.
     *
     * @param currency devise du paiement remboursé (code ISO à 3 lettres)
     */
    void markRetained(UUID bidId, CancellationFeeBreakdown breakdown, String currency);
}
