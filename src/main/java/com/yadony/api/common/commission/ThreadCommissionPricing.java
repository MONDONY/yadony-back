package com.yadony.api.common.commission;

import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.currency.SupportedCurrency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Commission d'un fil de demande d'envoi à grille figée (héritée de sa demande, spec §4.4) :
 * source unique du montant ANNONCÉ (événement {@code NegotiationCommissionPendingEvent}, contrôle
 * du solde du voyageur, devis) et du montant PRÉLEVÉ au règlement espèces
 * ({@code CashCommissionService.settleNegotiationCommission}) ou DÉPOSÉ en mobile money
 * ({@code NegotiationService.prepareMobileMoneyDeposit}). Les deux doivent être égaux.
 *
 * <p>Grille marginale, taux spécial et bon de parrainage rattachés au fil, code promo du fil pour
 * le seul rail mobile money (le rail espèces des fils n'en prend pas), minimum de la devise, un
 * seul arrondi à l'unité mineure (francs entiers en XOF/XAF). Lecture pure : ni le bon ni le code
 * ne sont consommés.
 */
@Component
public class ThreadCommissionPricing {

    private static final Logger log = LoggerFactory.getLogger(ThreadCommissionPricing.class);

    private final CommissionGridService gridService;
    private final CommissionRateResolver rateResolver;

    public ThreadCommissionPricing(CommissionGridService gridService, CommissionRateResolver rateResolver) {
        this.gridService = gridService;
        this.rateResolver = rateResolver;
    }

    /**
     * Devis de la commission du fil.
     *
     * @param gridId       grille figée sur le fil (non nulle : un fil sans grille garde le calcul hérité).
     * @param net          net du voyageur, dans la devise du fil.
     * @param currencyCode devise du fil ({@code negotiation_threads.currency}), EUR par défaut.
     * @param threadId     référence du bon de parrainage déjà rattaché au fil.
     * @return devis complet ; un net nul à l'unité mineure ne coûte rien (commission 0, brut = net),
     *         comme {@code net × taux} sur le chemin hérité.
     */
    public CommissionResult price(UUID gridId, BigDecimal net, String currencyCode,
                                  UUID travelerId, UUID senderId, UUID threadId) {
        return price(gridId, net, currencyCode, travelerId, senderId, threadId, null);
    }

    /**
     * Même devis, avec le code promo copié sur le fil (dépôt mobile money, comme la carte).
     *
     * @param promoCode code du fil, {@code null} ou blanc sans code. Un code devenu invalide
     *                  (expiré, limite atteinte…) retombe sur les réductions sans code, comme le
     *                  rail carte ; {@link CommissionResult#promoEffective()} dit alors faux, de
     *                  même quand le minimum annule tout le gain : le code ne doit pas être racheté.
     */
    public CommissionResult price(UUID gridId, BigDecimal net, String currencyCode,
                                  UUID travelerId, UUID senderId, UUID threadId, String promoCode) {
        SupportedCurrency currency = SupportedCurrency.fromCodeOrDefault(currencyCode);
        BigDecimal n = net.setScale(currency.minorUnit(), RoundingMode.HALF_UP);
        if (n.signum() <= 0) {
            BigDecimal zero = BigDecimal.ZERO.setScale(currency.minorUnit());
            return new CommissionResult(n, zero, n,
                    BigDecimal.ZERO.setScale(CommissionResult.STORED_RATE_SCALE), false, false);
        }
        return CommissionCalculator.compute(n, currency, gridService.require(gridId),
                reductions(travelerId, senderId, threadId, promoCode));
    }

    private CommissionReductions reductions(UUID travelerId, UUID senderId, UUID threadId, String promoCode) {
        if (promoCode == null || promoCode.isBlank()) {
            return rateResolver.reductions(travelerId, senderId, null, null, threadId);
        }
        try {
            return rateResolver.reductions(travelerId, senderId, promoCode, senderId, threadId);
        } catch (YadonyBusinessException e) {
            log.warn("Promo {} invalide pour la négociation {} ({}) : repli sur les réductions sans promo",
                    promoCode, threadId, e.getErrorCode());
            return rateResolver.reductions(travelerId, senderId, null, null, threadId);
        }
    }
}
