package com.yadony.api.payments.wallet;

import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import com.yadony.api.payments.currency.CurrencyBounds;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.referral.events.ReferralRewardGrantedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;

/**
 * Credits a referrer's spendable wallet balance when a referral reward is granted.
 *
 * <p>Listens to {@link ReferralRewardGrantedEvent} published by the referral package
 * (cross-package communication is event-driven only — CLAUDE.md rule #5). Uses
 * {@code AFTER_COMMIT} + a new transaction so the credit lands only once the reward
 * grant has committed (CLAUDE.md rule #18).
 *
 * <p>Idempotency key {@code referral-reward-{invitationId}} guarantees the parrain is
 * credited exactly once per invitation, even if the event is replayed.
 */
@Component
public class ReferralRewardWalletListener {

    private static final Logger log = LoggerFactory.getLogger(ReferralRewardWalletListener.class);

    private final WalletService walletService;
    private final ActiveCurrencyResolver activeCurrencyResolver;

    public ReferralRewardWalletListener(WalletService walletService,
                                        ActiveCurrencyResolver activeCurrencyResolver) {
        this.walletService = walletService;
        this.activeCurrencyResolver = activeCurrencyResolver;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onReferralRewardGranted(ReferralRewardGrantedEvent event) {
        // La récompense est versée dans la devise active du parrain au moment du
        // versement, sur le portefeuille correspondant. Elle était auparavant
        // toujours créditée en euros : un parrain travaillant en dollar recevait
        // une somme qu'il ne pouvait dépenser que sur des transactions en euros.
        String currencyCode = activeCurrencyResolver.resolve(event.referrerUserId());
        SupportedCurrency currency = SupportedCurrency.fromCodeOrDefault(currencyCode);

        // reward-amount-cents est un barème exprimé en centimes d'euro. Il est
        // converti pour garder sa VALEUR : 5 € valent environ 3 280 F CFA. Reprendre
        // le nombre tel quel aurait versé 5 XOF, soit moins d'un centime d'euro.
        //
        // Convertir un barème que la plateforme fixe elle-même ne contredit pas le
        // partitionnement des devises : celui-ci interdit de convertir un montant
        // échangé entre deux utilisateurs, pas d'exprimer un cadeau maison dans la
        // devise de son destinataire.
        BigDecimal nominalEur = BigDecimal.valueOf(event.amountCents()).movePointLeft(2);
        BigDecimal amount = CurrencyBounds.scaleFromEur(nominalEur, currency);
        String idempotencyKey = "referral-reward-" + event.invitationId();

        walletService.credit(
                event.referrerUserId(),
                currencyCode,
                amount,
                WalletTransactionType.REFERRAL_REWARD,
                event.invitationId().toString(),
                idempotencyKey);

        log.info("Referral reward credited to wallet: referrer={} amount={} currency={} invitation={}",
                event.referrerUserId(), amount, currencyCode, event.invitationId());
    }
}
