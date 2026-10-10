package com.yadony.api.cancellation;

import java.util.UUID;

/**
 * Libération de l'argent d'un bid encore {@code AWAITING_PAYMENT}, pour l'annulation de
 * l'expéditeur avant paiement ({@link PrePaymentCancellationService}).
 *
 * <p>Le paquet {@code cancellation} décide de l'annulation ; le paquet {@code payments}, seul à
 * parler à Stripe et à pawaPay, l'implémente. Port plutôt qu'événement : l'annulation doit
 * savoir, AVANT de passer le bid en {@code CANCELLED}, si l'argent est déjà parti (réponse 409),
 * ce qu'un écouteur asynchrone ne peut pas lui dire.
 *
 * <p><b>Contrat de verrou</b> : appelé dans la transaction de l'annulation, APRÈS le verrou du
 * colis. L'implémentation verrouille la ligne du paiement : ordre colis puis paiement, celui de la
 * livraison et de l'annulation admin. La confirmation d'un dépôt mobile money verrouille dans
 * l'ordre inverse ; un interblocage avec elle est détecté par PostgreSQL et l'annulation répond
 * 409 {@code payment-in-progress}.
 */
public interface PrePaymentReleasePort {

    enum Outcome {
        /** Une autorisation carte a été annulée ou un paiement en attente a été clos : rien n'est débité. */
        RELEASED,
        /** Aucun argent engagé (pas de paiement, ou déjà annulé / échoué) : rien à libérer. */
        NOTHING_TO_RELEASE,
        /** Le paiement est déjà autorisé, encaissé ou en séquestre : ce chemin ne s'applique plus. */
        ALREADY_PAID,
        /** Un paiement est en cours de validation (dépôt mobile money ouvert, carte en traitement). */
        PAYMENT_IN_PROGRESS
    }

    /**
     * @param bidId              le bid {@code AWAITING_PAYMENT} à annuler
     * @param bidPaymentIntentId PaymentIntent porté par le bid (peut différer de celui du
     *                           paiement, ou être nul)
     * @param actorId            l'expéditeur qui annule (trace d'audit)
     */
    Outcome releaseBeforeCancellation(UUID bidId, String bidPaymentIntentId, UUID actorId);
}
