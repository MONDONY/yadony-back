package com.yadony.api.payments.wallet;

import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import com.yadony.api.payments.currency.CurrencyAmount;
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
        //
        // Le montant nominal est repris tel quel, sans conversion : 5 devient
        // 5 USD et non l'équivalent de 5 EUR. C'est cohérent avec le
        // partitionnement strict des devises, qui interdit toute conversion.
        String currencyCode = activeCurrencyResolver.resolve(event.referrerUserId());
        SupportedCurrency currency = SupportedCurrency.fromCodeOrDefault(currencyCode);

        // amountCents est exprimé en centimes d'euro : c'est une valeur de
        // configuration, pas un montant déjà dans la devise cible. On en retire
        // la valeur nominale (500 → 5) avant de la porter à la précision de la
        // devise, le franc CFA n'ayant pas de sous-unité.
        BigDecimal nominal = BigDecimal.valueOf(event.amountCents()).movePointLeft(2);
        BigDecimal amount = CurrencyAmount.of(nominal, currency).major();
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
