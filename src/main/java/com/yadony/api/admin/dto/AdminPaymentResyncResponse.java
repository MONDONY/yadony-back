package com.yadony.api.admin.dto;

import com.yadony.api.payments.PaymentStripeResyncService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Réponse de {@code POST /admin/payments/{id}/resync-stripe}.
 *
 * @param action            {@code ALREADY_IN_SYNC}, {@code ESCROW_ACTIVATED}, {@code ESCROW_CAPTURED},
 *                          {@code CAPTURE_RECORDED}, {@code MARKED_FAILED}, {@code MARKED_CANCELLED} ou
 *                          {@code ESCROW_RELEASED} (ajouté : séquestre déjà en place, seul le versement
 *                          d'un colis déjà livré restait à faire)
 * @param changed           faux pour {@code ALREADY_IN_SYNC} (aucune écriture)
 * @param resolvedAlertIds  alertes du paiement résolues automatiquement par cette resynchronisation
 * @param openAlertIds      alertes du paiement encore ouvertes (écart d'une autre nature : à traiter à la main)
 * @param alertResolvable   vrai s'il reste des alertes ouvertes que l'admin peut clore lui-même
 *                          maintenant que la base est alignée sur Stripe
 * @param released          vrai si le versement au voyageur est parti pendant la resynchronisation (colis
 *                          déjà livré). Ajouté en fin d'objet : un back-office qui l'ignore reste compatible
 */
public record AdminPaymentResyncResponse(
        UUID paymentId,
        String paymentIntentId,
        String action,
        boolean changed,
        State before,
        State after,
        String message,
        List<UUID> resolvedAlertIds,
        List<UUID> openAlertIds,
        boolean alertResolvable,
        boolean released
) {
    /** État base + Stripe d'un paiement. */
    public record State(String status, Instant capturedAt, String stripeStatus, Long amountCapturable) {
        static State of(PaymentStripeResyncService.Snapshot s) {
            return new State(s.status(), s.capturedAt(), s.stripeStatus(), s.amountCapturable());
        }
    }

    public static AdminPaymentResyncResponse of(PaymentStripeResyncService.Result r, List<UUID> resolved,
                                                List<UUID> open) {
        return new AdminPaymentResyncResponse(r.paymentId(), r.paymentIntentId(), r.action().name(), r.changed(),
                State.of(r.before()), State.of(r.after()), r.message(), List.copyOf(resolved), List.copyOf(open),
                !open.isEmpty(), r.released());
    }
}
