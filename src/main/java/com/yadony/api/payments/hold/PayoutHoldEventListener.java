package com.yadony.api.payments.hold;

import com.yadony.api.auth.events.UserBannedEvent;
import com.yadony.api.auth.events.UserReinstatedEvent;
import com.yadony.api.kyc.events.UserKycRevokedEvent;
import com.yadony.api.kyc.events.UserKycVerifiedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Traduit les decisions de compte ({@code auth/}) et d'identite ({@code kyc/}) en gel ou degel
 * des versements.
 *
 * <p>{@code @EventListener} SYNCHRONE, volontairement, et non {@code AFTER_COMMIT} + {@code @Async}
 * comme les ecouteurs de paiement : le gel doit etre commite dans la meme transaction que le
 * bannissement ou la revocation. Un ecouteur differe laisserait une fenetre ou une livraison
 * confirmee juste apres le bannissement paierait encore le voyageur, et un echec du gel serait
 * perdu en silence. Un echec du gel fait ici echouer le geste de l'administrateur, qui le voit.
 *
 * <p>La finalisation d'un compte (suppression RGPD) ne gele rien : elle ne publie pas
 * {@link UserBannedEvent} et a son propre traitement ({@code UserFinalizedPaymentsListener}).
 */
@Component
public class PayoutHoldEventListener {

    private final PayoutHoldService holds;

    public PayoutHoldEventListener(PayoutHoldService holds) {
        this.holds = holds;
    }

    @EventListener
    public void onUserBanned(UserBannedEvent event) {
        holds.hold(event.userId(), PayoutHoldReason.BANNED, event.adminId());
    }

    @EventListener
    public void onKycRevoked(UserKycRevokedEvent event) {
        holds.hold(event.userId(), PayoutHoldReason.KYC_REVOKED, event.adminId());
    }

    @EventListener
    public void onUserReinstated(UserReinstatedEvent event) {
        holds.release(event.userId(), PayoutHoldReason.BANNED, event.adminId());
    }

    /** Toute nouvelle verification, par le fournisseur ou par un administrateur. */
    @EventListener
    public void onKycVerified(UserKycVerifiedEvent event) {
        holds.release(event.getUserId(), PayoutHoldReason.KYC_REVOKED, null);
    }
}
