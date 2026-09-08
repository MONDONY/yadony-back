package com.yadony.api.payments.mobilemoney;

import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidGridItemRepository;
import com.yadony.api.payments.PriceBreakdown;
import com.yadony.api.payments.pawapay.PawapayAmounts;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Brut / commission / net d'un bid mobile money, avec les mêmes règles que le rail cash
 * ({@code CashCommissionService#computeBidCommission}) : accord négocié figé, sinon
 * (poids × prix/kg + articles) × taux résolu (promo > overrides > global, bon de parrainage).
 * Tout est arrondi à l'unité mineure de la devise (XOF/XAF : aucune décimale) pour que
 * {@code net = brut − commission} reste exact au payout. Seul écart assumé avec le rail cash :
 * aucun plancher de commission ({@code yadony.commission.minimum-amount}, un montant en euros
 * qui n'aurait pas de sens en francs CFA).
 */
@Component
public class MobileMoneyBidPricing {

    private static final Logger log = LoggerFactory.getLogger(MobileMoneyBidPricing.class);

    /**
     * Prix calculé, et si le code promo porté par le bid a effectivement été pris dans le taux
     * — c'est alors à l'acceptation de le racheter ({@code PromoService#redeem}), jamais ici
     * (lecture pure). Même contrat que {@code BidQuoteResponse#promoApplied} sur le rail carte.
     */
    public record Quote(PriceBreakdown price, boolean promoApplied) {}

    private final BidGridItemRepository gridItems;
    private final CommissionRateResolver rates;

    public MobileMoneyBidPricing(BidGridItemRepository gridItems, CommissionRateResolver rates) {
        this.gridItems = gridItems;
        this.rates = rates;
    }

    public Quote price(BidEntity bid, AnnouncementEntity announcement) {
        String currency = announcement.getCurrency();
        if (bid.getNegotiatedNetEur() != null && bid.getNegotiatedGrossEur() != null) {
            BigDecimal net = PawapayAmounts.round(bid.getNegotiatedNetEur(), currency);
            BigDecimal gross = PawapayAmounts.round(bid.getNegotiatedGrossEur(), currency);
            return new Quote(new PriceBreakdown(net, gross.subtract(net), gross), false);
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
        BigDecimal net;
        if (bid.getNegotiatedNetEur() != null) {
            net = bid.getNegotiatedNetEur();
        } else {
            BigDecimal kgNet = bid.getWeightKg() != null && announcement.getPricePerKg() != null
                    ? bid.getWeightKg().multiply(announcement.getPricePerKg()) : BigDecimal.ZERO;
            BigDecimal gridNet = gridItems.findByBidId(bid.getId()).stream()
                    .map(i -> i.getUnitPriceNetSnapshot().multiply(BigDecimal.valueOf(i.getQuantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            net = kgNet.add(gridNet);
        }
        net = PawapayAmounts.round(net, currency);
        BigDecimal commission = PawapayAmounts.round(net.multiply(rate), currency);
        return new Quote(new PriceBreakdown(net, commission, net.add(commission)), promoApplied);
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
