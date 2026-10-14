package com.yadony.api.payments.wallet;

import com.yadony.api.referral.events.ReferralWalletRewardChosenEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Crédite le solde du parrain qui a choisi la récompense « solde » (V321).
 *
 * <p>Écoute {@link ReferralWalletRewardChosenEvent} publié par le paquet {@code referral}
 * (communication entre paquets par événements). {@code AFTER_COMMIT} + nouvelle
 * transaction : le crédit n'a lieu qu'une fois le choix validé. La clé d'idempotence
 * {@code referral-reward:<invitationId>} garantit un seul crédit par invitation, même si
 * l'événement est rejoué.
 *
 * <p>{@link WalletTransactionType#REFERRAL_REWARD} est un crédit non-cash : il se dépense
 * sur l'application mais n'est pas remboursable vers une carte.
 */
@Component
public class ReferralRewardWalletListener {

    private static final Logger log = LoggerFactory.getLogger(ReferralRewardWalletListener.class);

    private final WalletService walletService;

    public ReferralRewardWalletListener(WalletService walletService) {
        this.walletService = walletService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onWalletRewardChosen(ReferralWalletRewardChosenEvent event) {
        try {
            walletService.credit(
                    event.referrerUserId(),
                    event.currency(),
                    event.amount(),
                    WalletTransactionType.REFERRAL_REWARD,
                    event.invitationId().toString(),
                    event.idempotencyKey());
            log.info("Referral reward credited: referrer={} amount={} {} invitation={}",
                    event.referrerUserId(), event.amount(), event.currency(), event.invitationId());
        } catch (RuntimeException ex) {
            // Le choix est déjà validé : sans ce log, un crédit raté passerait inaperçu.
            // Rejouer l'événement est sans risque (clé d'idempotence).
            log.error("Referral reward wallet credit FAILED: referrer={} invitation={} amount={} {}",
                    event.referrerUserId(), event.invitationId(), event.amount(), event.currency(), ex);
            throw ex;
        }
    }
}
