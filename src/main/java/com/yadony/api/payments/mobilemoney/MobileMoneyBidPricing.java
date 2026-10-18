package com.yadony.api.payments.mobilemoney;

import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.commission.CommissionCalculator;
import com.yadony.api.common.commission.CommissionGrid;
import com.yadony.api.common.commission.CommissionGridService;
import com.yadony.api.common.commission.CommissionReductions;
import com.yadony.api.common.commission.CommissionResult;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidGridItemRepository;
import com.yadony.api.payments.PriceBreakdown;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.payments.pawapay.PawapayAmounts;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Brut / commission / net d'un bid mobile money, avec les mêmes règles que le rail cash
 * ({@code CashCommissionService#quoteBidCommission}) : accord négocié figé, sinon
 * (poids × prix/kg + articles) tarifé. Tout est arrondi à l'unité mineure de la devise
 * (XOF/XAF : aucune décimale) pour que {@code net = brut − commission} reste exact au payout.
 *
 * <p><b>Bid à grille</b> ({@code commission_grid_id} figé à sa création, spec §4.4) : commission
 * de la grille figée par {@link CommissionCalculator}, minimum de la devise compris (650 FCFA par
 * défaut), comme les rails carte et espèces ; voir {@link #priceWithGrid}.
 *
 * <p><b>Bid sans grille</b> (chemin hérité, inchangé) : net × taux résolu (promo > overrides >
 * global, bon de parrainage), sans aucun plancher de commission
 * ({@code yadony.commission.minimum-amount} n'est appliqué que par le rail cash).
 */
@Component
public class MobileMoneyBidPricing {

    private static final Logger log = LoggerFactory.getLogger(MobileMoneyBidPricing.class);

    /**
     * Prix calculé, et si le code promo porté par le bid a effectivement été pris dans le taux
     * — c'est alors à l'acceptation de le racheter ({@code PromoService#redeem}), jamais ici
     * (lecture pure). Même contrat que {@code BidQuoteResponse#promoApplied} sur le rail carte.
     *
     * @param promoApplied   code validé et pris dans le calcul ; sur un bid à grille, vaut
     *                       {@code promoEffective}.
     * @param promoEffective le code baisse réellement la commission : faux quand le minimum de la
     *                       grille annule tout le gain (spec §4.2) ; seule condition du rachat.
     *                       Sur le chemin hérité, identique à {@code promoApplied}.
     */
    public record Quote(PriceBreakdown price, boolean promoApplied, boolean promoEffective) {

        /** Chemin hérité : un code pris dans le taux baisse toujours la commission. */
        Quote(PriceBreakdown price, boolean promoApplied) {
            this(price, promoApplied, promoApplied);
        }
    }

    private final BidGridItemRepository gridItems;
    private final CommissionRateResolver rates;
    private final CommissionGridService gridService;

    public MobileMoneyBidPricing(BidGridItemRepository gridItems, CommissionRateResolver rates,
                                 CommissionGridService gridService) {
        this.gridItems = gridItems;
        this.rates = rates;
        this.gridService = gridService;
    }

    public Quote price(BidEntity bid, AnnouncementEntity announcement) {
        String currency = announcement.getCurrency();
        if (bid.getNegotiatedNetEur() != null && bid.getNegotiatedGrossEur() != null) {
            BigDecimal net = PawapayAmounts.round(bid.getNegotiatedNetEur(), currency);
            BigDecimal gross = PawapayAmounts.round(bid.getNegotiatedGrossEur(), currency);
            return new Quote(new PriceBreakdown(net, gross.subtract(net), gross), false);
        }
        if (bid.getCommissionGridId() != null) {
            return priceWithGrid(bid, announcement);
        }
        BigDecimal rate;
        boolean promoApplied = false;
        if (bid.getNegotiatedNetEur() != null && bid.getCommissionRate() != null) {
            rate = bid.getCommissionRate();
        } else {
            ResolvedRate resolved = resolveRate(bid, announcement);
            rate = resolved.rate();
            promoApplied = resolved.promoApplied();
        }
        BigDecimal net = bid.getNegotiatedNetEur() != null ? bid.getNegotiatedNetEur() : scheduleNet(bid, announcement);
        net = PawapayAmounts.round(net, currency);
        BigDecimal commission = PawapayAmounts.round(net.multiply(rate), currency);
        return new Quote(new PriceBreakdown(net, commission, net.add(commission)), promoApplied);
    }

    /**
     * Bid à grille figée, sur le modèle du rail espèces ({@code quoteGridBidCommission}) :
     * <ul>
     *   <li><b>Net négocié seul</b> : le net convenu, avec les réductions figées sur le bid à
     *       l'ouverture du fil (taux spécial, bon), sans code promo ni résolveur du jour ; le taux
     *       figé au fil n'est pas réécrit.</li>
     *   <li><b>Sinon</b> : le net du barème et les réductions du jour pour ce bid, un code promo
     *       invalide retombant sur les réductions sans code. Le taux effectif est figé sur le bid,
     *       borné sous 1 ({@link CommissionResult#storableRate()}) : c'est le taux du rachat du
     *       code, les montants (portés par le paiement) font foi.</li>
     * </ul>
     * Un net nul est refusé (422 {@code invalid-amount}, comme les rails carte et espèces) : la
     * grille ne tarife pas un net nul.
     */
    private Quote priceWithGrid(BidEntity bid, AnnouncementEntity announcement) {
        SupportedCurrency currency = SupportedCurrency.fromCodeOrDefault(announcement.getCurrency());
        CommissionGrid grid = gridService.require(bid.getCommissionGridId());
        if (bid.getNegotiatedNetEur() != null) {
            CommissionResult result = CommissionCalculator.compute(requirePositive(bid.getNegotiatedNetEur(), currency),
                    currency, grid,
                    new CommissionReductions(bid.getCommissionOverrideRate(), null, bid.getCommissionVoucherFactor()));
            return new Quote(new PriceBreakdown(result.net(), result.commission(), result.gross()), false);
        }
        CommissionResult result = CommissionCalculator.compute(requirePositive(scheduleNet(bid, announcement), currency),
                currency, grid, rates.reductionsWithPromoFallback(announcement.getTravelerId(), bid.getSenderId(),
                        bid.getPromoCode(), bid.getId()));
        bid.setCommissionRate(result.storableRate());
        return new Quote(new PriceBreakdown(result.net(), result.commission(), result.gross()),
                result.promoEffective(), result.promoEffective());
    }

    /** Net du barème : poids × prix/kg (absent en mode GRID pur) + articles snapshotés du bid. */
    private BigDecimal scheduleNet(BidEntity bid, AnnouncementEntity announcement) {
        BigDecimal kgNet = bid.getWeightKg() != null && announcement.getPricePerKg() != null
                ? bid.getWeightKg().multiply(announcement.getPricePerKg()) : BigDecimal.ZERO;
        BigDecimal gridNet = gridItems.findByBidId(bid.getId()).stream()
                .map(i -> i.getUnitPriceNetSnapshot().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return kgNet.add(gridNet);
    }

    /** Net strictement positif à l'unité mineure de la devise, sinon 422 {@code invalid-amount}. */
    private static BigDecimal requirePositive(BigDecimal net, SupportedCurrency currency) {
        if (net.setScale(currency.minorUnit(), RoundingMode.HALF_UP).signum() <= 0) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "invalid-amount", "Invalid Amount", "Le montant calculé est invalide (≤ 0)");
        }
        return net;
    }

    private record ResolvedRate(BigDecimal rate, boolean promoApplied) {}

    private ResolvedRate resolveRate(BidEntity bid, AnnouncementEntity announcement) {
        BigDecimal rate;
        boolean promoApplied = false;
        if (bid.getPromoCode() != null) {
            try {
                rate = rates.resolve(announcement.getTravelerId(), bid.getSenderId(), bid.getPromoCode(),
                        bid.getSenderId(), bid.getId());
                promoApplied = true;
            } catch (YadonyBusinessException e) {
                log.warn("Promo {} invalide pour le bid mobile money {} — repli", bid.getPromoCode(), bid.getId());
                rate = rates.resolve(announcement.getTravelerId(), bid.getSenderId(), null, null, bid.getId());
            }
        } else {
            rate = rates.resolve(announcement.getTravelerId(), bid.getSenderId(), null, null, bid.getId());
        }
        // Le taux PERSISTÉ (ci-dessous) et le taux UTILISÉ pour le calcul de la commission
        // qui suit doivent être IDENTIQUES — jamais l'un arrondi et l'autre non. Le rail
        // espèces (CashCommissionService#computeBidCommission) n'arrondit jamais ce taux
        // avant de s'en servir ; un ré-arrondi à 4 décimales ici ferait diverger
        // silencieusement les deux rails dès qu'un taux dérogatoire (override, promo)
        // porterait plus de décimales que ce qui est utilisé pour calculer la commission.
        // Cette identité ne vaut qu'EN MÉMOIRE, pour ce seul appel : bids.commission_rate est
        // en base DECIMAL(4,3) (V117), donc un taux à plus de 3 décimales est silencieusement
        // arrondi par PostgreSQL à la sauvegarde puis au rechargement — sans conséquence
        // monétaire, puisque c'est payments.commission_amount (déjà calculé ici, avec le taux
        // complet, avant toute sauvegarde) qui fait foi pour le versement, jamais une
        // relecture de ce taux snapshoté.
        bid.setCommissionRate(rate);
        return new ResolvedRate(rate, promoApplied);
    }
}
