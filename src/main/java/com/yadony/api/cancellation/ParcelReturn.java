package com.yadony.api.cancellation;

import com.yadony.api.matching.BidEntity;

import java.security.SecureRandom;
import java.time.LocalDateTime;

/**
 * Ouverture de la procédure de retour d'un colis déjà remis au voyageur (D7).
 *
 * <p>Source unique des trois flux qui annulent un colis que le voyageur a en main :
 * annulation après remise ({@link CancellationService#cancelAfterHandover}), annulation du
 * trajet entier ({@link CancellationService#cancelTrip}, FLUTTER-FH) et retrait de
 * l'expéditeur après un report ({@link RescheduleDecisionService}). Le code est détenu par
 * l'expéditeur et saisi par le voyageur à la restitution ; le délai de retour est suivi par
 * {@code ReturnDeadlineScheduler}.
 */
final class ParcelReturn {

    /** Délai de restitution, en jours, et durée de validité du code de retour. */
    static final int RETURN_DAYS = 3;

    private static final SecureRandom RANDOM = new SecureRandom();

    private ParcelReturn() {
    }

    /** Pose le code de retour, sa validité et le délai de retour, puis passe le bid en CANCELLED. */
    static void open(BidEntity bid, LocalDateTime now) {
        bid.setReturnCode(String.format("%06d", RANDOM.nextInt(1_000_000)));
        bid.setReturnCodeExpiry(now.plusDays(RETURN_DAYS));
        bid.setReturnCodeAttempts(0);
        bid.setReturnDeadline(now.plusDays(RETURN_DAYS));
        bid.setStatus(com.yadony.api.matching.BidStatus.CANCELLED);
    }
}
