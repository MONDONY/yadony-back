package com.yadony.api.payments.wallet.fees;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.stripe.exception.StripeException;
import com.stripe.model.BalanceTransaction;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentRetrieveParams;
import com.yadony.api.payments.currency.SupportedCurrency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;

/**
 * Frais Stripe réellement prélevés sur une recharge, lus sur la transaction de solde
 * ({@code PaymentIntent.latest_charge.balance_transaction.fee}) plutôt que recalculés :
 * ce montant dépend du type de carte et du pays émetteur, un taux fixe s'en écarterait.
 *
 * <p>{@link #fee(String, String)} renvoie {@code null} (jamais mis en cache) quand le
 * frais réel est illisible : Stripe injoignable, transaction de solde absente, ou
 * transaction dans une devise différente de celle du remboursement demandé (un
 * paiement d'origine réglé dans une autre devise que le wallet remboursé). L'appelant
 * doit alors utiliser {@link #fallback(BigDecimal, String)}.
 */
@Component
public class StripeFeeSource {

    private static final Logger log = LoggerFactory.getLogger(StripeFeeSource.class);

    private final Cache<String, BigDecimal> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(1))
            .maximumSize(5_000)
            .build();

    private final BigDecimal fallbackPercent;
    private final BigDecimal fallbackFixed;

    public StripeFeeSource(
            @Value("${yadony.wallet.refund-fee.stripe-fallback.percent:3.15}") BigDecimal fallbackPercent,
            @Value("${yadony.wallet.refund-fee.stripe-fallback.fixed:0.25}") BigDecimal fallbackFixed) {
        this.fallbackPercent = fallbackPercent;
        this.fallbackFixed = fallbackFixed;
    }

    public BigDecimal fee(String paymentIntentId, String currency) {
        BigDecimal cached = cache.getIfPresent(paymentIntentId);
        if (cached != null) {
            return cached;
        }
        int scale = SupportedCurrency.fromCodeOrDefault(currency).minorUnit();
        try {
            PaymentIntent pi = PaymentIntent.retrieve(paymentIntentId,
                    PaymentIntentRetrieveParams.builder().addExpand("latest_charge.balance_transaction").build(),
                    null);
            Charge charge = pi.getLatestChargeObject();
            BalanceTransaction balanceTransaction = charge != null ? charge.getBalanceTransactionObject() : null;
            if (balanceTransaction != null && balanceTransaction.getFee() != null
                    && currency.equalsIgnoreCase(balanceTransaction.getCurrency())) {
                BigDecimal fee = BigDecimal.valueOf(balanceTransaction.getFee(), scale);
                cache.put(paymentIntentId, fee);
                return fee;
            }
        } catch (StripeException e) {
            log.warn("Frais Stripe illisibles pour {} ({}), repli sur le taux configuré",
                    paymentIntentId, e.getCode());
        }
        return null;
    }

    /** Taux configuré : {@code amount x percent / 100 + fixed}, arrondi à l'échelle de la devise. */
    public BigDecimal fallback(BigDecimal amount, String currency) {
        int scale = SupportedCurrency.fromCodeOrDefault(currency).minorUnit();
        return amount.multiply(fallbackPercent)
                .divide(new BigDecimal("100"), scale, RoundingMode.HALF_UP)
                .add(fallbackFixed)
                .setScale(scale, RoundingMode.HALF_UP);
    }
}
