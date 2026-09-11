package com.yadony.api.requests;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Argent du rail mobile money sur un fil de négociation, vu depuis {@code requests/}.
 * Implémenté dans {@code payments/} ({@code NegotiationMobileMoneyAdapter}), même
 * découpage que {@link NegotiationEscrowPort} pour la carte. Le fil reste piloté ici ;
 * le port ne touche jamais au statut du fil.
 */
public interface NegotiationMobileMoneyPort {

    /** Paiement PENDING créé (ou réutilisé) pour ce fil, montants dans la devise du fil, échéance du dépôt. */
    record PendingDeposit(UUID paymentId, BigDecimal gross, BigDecimal commission, LocalDateTime expiresAt) {}

    enum ReleaseOutcome {
        /** Paiement PENDING annulé : le fil peut revenir à AWAITING_PAYMENT. */
        CANCELLED,
        /** Aucun paiement PENDING (jamais créé, déjà annulé) : le fil peut revenir à AWAITING_PAYMENT. */
        NOTHING_PENDING,
        /** Un dépôt pawaPay est encore en vol (PIN en cours de saisie) : ne rien faire, attendre. */
        DEPOSIT_OPEN,
        /**
         * Dépôt COMPLETED côté pawaPay mais paiement encore PENDING : la confirmation du
         * séquestre s'est perdue. Ne pas libérer ; réparable par
         * {@link #repairDepositCompletedNotApplied}.
         */
        DEPOSIT_COMPLETED_NOT_APPLIED,
        /**
         * Séquestre posé (paiement ESCROW) mais le scellement du fil n'est pas encore passé :
         * en vol dans sa propre transaction, ou perdu. Ne pas libérer ; réparable par un rejeu
         * de {@code finalizeAfterMobileMoneyDeposit} côté {@code requests/}.
         */
        ESCROW_NOT_SEALED
    }

    /**
     * Crée le paiement PENDING keyé sur le fil (rail PAWAPAY), dans la transaction appelante.
     * Idempotent : un PENDING existant est rendu tel quel, un CANCELLED est recyclé.
     * Ne soumet JAMAIS rien à pawaPay.
     */
    PendingDeposit createPendingDeposit(UUID threadId, UUID senderId, UUID travelerId,
                                        BigDecimal net, BigDecimal commissionRate, String currency);

    /** Annule le paiement PENDING du fil si aucun dépôt n'est en vol. */
    ReleaseOutcome releasePendingDeposit(UUID threadId);

    /**
     * Répare {@link ReleaseOutcome#DEPOSIT_COMPLETED_NOT_APPLIED} : rejoue la confirmation du
     * séquestre sur le dernier dépôt COMPLETED du paiement PAWAPAY du fil (idempotent, un seul
     * gagnant côté claim). Le rejeu republie l'événement de confirmation qui scellera le fil.
     *
     * @throws IllegalStateException si le paiement PAWAPAY ou le dépôt COMPLETED a disparu
     *         entre le diagnostic et la réparation (état incohérent, à alerter par l'appelant).
     */
    void repairDepositCompletedNotApplied(UUID threadId);

    /**
     * Le séquestre existe mais le fil n'est plus scellable (auto-rejeté, annulé) : ESCROW → REFUNDED
     * et remboursement pawaPay du dépôt. {@code true} si un remboursement a été soumis.
     */
    boolean refundEscrowedDeposit(UUID threadId);
}
