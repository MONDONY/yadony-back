package com.yadony.api.payments.fee;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Versement du dédommagement d'annulation au voyageur, vu du paquet {@code payments}.
 *
 * <p>La ligne de la retenue appartient au paquet {@code cancellation} ; {@code payments} la lit et la fait
 * avancer par ce port, qu'il possède et que {@code cancellation} implémente (même inversion de dépendance
 * que {@link CancellationFeeRatePort}). Cycle : {@code RETAINED} (retenue réglée au remboursement) puis
 * {@code PAID_OUT} (versé, ou rien à verser) ou {@code PAYOUT_PENDING} (essai en échec, repris chaque heure)
 * puis {@code PAID_OUT}. Jamais de retour en arrière : une ligne versée le reste.
 */
public interface CancellationFeePayoutPort {

    /**
     * @return la ligne si elle est encore à verser ({@code RETAINED} ou {@code PAYOUT_PENDING}) ; vide si elle
     *         est déjà versée, close, encore due ou inexistante
     */
    Optional<PayableCancellationFee> findPayable(UUID feeId);

    /** Comme {@link #findPayable}, pour la retenue d'un colis. */
    Optional<PayableCancellationFee> findPayableByBid(UUID bidId);

    /**
     * Lignes à reprendre : toutes les {@code PAYOUT_PENDING}, et les {@code RETAINED} réglées avant
     * {@code retainedBefore} (l'écouteur du règlement n'a jamais abouti : redéploiement, plantage). Les plus
     * anciennes d'abord.
     */
    List<UUID> findPayoutRetries(Instant retainedBefore);

    /**
     * Passe la ligne en {@code PAID_OUT} sous verrou de ligne, depuis {@code RETAINED} ou {@code PAYOUT_PENDING},
     * et l'audite ({@code CANCELLATION_FEE_PAID_OUT}) au seul passage. Idempotent.
     *
     * @param payoutRef référence du versement (Transfer Stripe), {@code null} quand rien n'a été versé (net nul)
     * @return {@code true} si la ligne est versée à l'issue de l'appel (par cet appel ou un précédent)
     */
    boolean markPaidOut(UUID feeId, String payoutRef);

    /**
     * Passe la ligne en {@code PAYOUT_PENDING} sous verrou de ligne, depuis {@code RETAINED} seulement, et
     * l'audite ({@code CANCELLATION_FEE_PAYOUT_PENDING}) au seul passage. Ne touche jamais une ligne versée.
     *
     * @param reason motif court de l'échec, repris dans l'audit
     * @return {@code true} si la ligne est en attente à l'issue de l'appel (par cet appel ou un précédent),
     *         {@code false} si elle n'est plus à verser (déjà versée par un essai concurrent, close, absente)
     */
    boolean markPayoutPending(UUID feeId, String reason);
}
