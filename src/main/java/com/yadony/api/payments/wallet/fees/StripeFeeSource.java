package com.yadony.api.payments.wallet.fees;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.stripe.exception.StripeException;
import com.stripe.model.BalanceTransaction;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
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
 *
 * <p>Contrat : {@code fee()} ne lève jamais. Toute erreur, y compris une
 * {@link RuntimeException} imprévue levée par le SDK Stripe lors de la navigation
 * ({@code retrieve}, {@code getLatestChargeObject}, {@code getBalanceTransactionObject}),
 * aboutit au même repli que {@link StripeException} plutôt que de remonter et de faire
 * échouer l'allocation de remboursement en cours.
 */
@Component
public class StripeFeeSource {

    private static final Logger log = LoggerFactory.getLogger(StripeFeeSource.class);

    /**
     * Frais réels lus. Le frais d'une charge passée ne change plus : une heure de rétention
     * relançait, chaque heure, un appel Stripe par recharge carte de l'historique à chaque
     * affichage du portefeuille.
     */
    private final Cache<String, BigDecimal> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(24))
            .maximumSize(20_000)
            .build();

    /**
     * Échecs récents, pour l'affichage seulement ({@link #feeForDisplay}) : Stripe lent ou
     * injoignable n'est pas réinterrogé recharge par recharge à chaque ouverture de l'écran.
     */
    private final Cache<String, Boolean> recentDisplayFailures = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(2))
            .maximumSize(5_000)
            .build();

    /**
     * Délais de l'affichage : sans eux, le SDK attend jusqu'à 30 s pour se connecter et 80 s
     * pour lire, et l'écran portefeuille avec lui. Le repli configuré prend le relais.
     */
    private static final RequestOptions DISPLAY_OPTIONS = RequestOptions.builder()
            .setConnectTimeout(2_000)
            .setReadTimeout(5_000)
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
        return lookup(paymentIntentId, currency, null);
    }

    /**
     * Variante de l'affichage du portefeuille : délais courts, et un échec récent n'est pas
     * retenté pendant deux minutes (repli configuré à la place). Le chemin d'un remboursement
     * réel ({@link #fee}) garde ses délais et retente à chaque fois.
     */
    public BigDecimal feeForDisplay(String paymentIntentId, String currency) {
        String cacheKey = paymentIntentId + "|" + currency;
        if (recentDisplayFailures.getIfPresent(cacheKey) != null) {
            return cache.getIfPresent(cacheKey);
        }
        BigDecimal fee = lookup(paymentIntentId, currency, DISPLAY_OPTIONS);
        if (fee == null) {
            recentDisplayFailures.put(cacheKey, Boolean.TRUE);
        }
        return fee;
    }

    private BigDecimal lookup(String paymentIntentId, String currency, RequestOptions options) {
        String cacheKey = paymentIntentId + "|" + currency;
        BigDecimal cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }
        int scale = SupportedCurrency.fromCodeOrDefault(currency).minorUnit();
        try {
            PaymentIntent pi = PaymentIntent.retrieve(paymentIntentId,
                    PaymentIntentRetrieveParams.builder().addExpand("latest_charge.balance_transaction").build(),
                    options);
            Charge charge = pi.getLatestChargeObject();
            BalanceTransaction balanceTransaction = charge != null ? charge.getBalanceTransactionObject() : null;
            if (balanceTransaction != null && balanceTransaction.getFee() != null
                    && currency.equalsIgnoreCase(balanceTransaction.getCurrency())) {
                BigDecimal fee = BigDecimal.valueOf(balanceTransaction.getFee(), scale);
                cache.put(cacheKey, fee);
                return fee;
            }
        } catch (StripeException e) {
            log.warn("Frais Stripe illisibles pour {} ({}), repli sur le taux configuré",
                    paymentIntentId, e.getCode());
        } catch (RuntimeException e) {
            // Le SDK Stripe (retrieve, getLatestChargeObject, getBalanceTransactionObject) peut
            // lever une RuntimeException imprévue (désérialisation, réponse malformée...) sans
            // passer par StripeException. Contrat de cette méthode : ne jamais propager, toujours
            // replier. Pas de stack ni de contenu potentiellement sensible dans le log.
            log.warn("Frais Stripe illisibles pour {} ({}), repli sur le taux configuré",
                    paymentIntentId, e.getClass().getSimpleName());
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
