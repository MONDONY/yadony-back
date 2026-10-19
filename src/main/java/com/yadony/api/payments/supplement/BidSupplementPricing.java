package com.yadony.api.payments.supplement;

import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.common.commission.CommissionCalculator;
import com.yadony.api.common.commission.CommissionGrid;
import com.yadony.api.common.commission.CommissionGridService;
import com.yadony.api.common.commission.CommissionReductions;
import com.yadony.api.common.commission.CommissionResult;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidGridItemRepository;
import com.yadony.api.matching.BidNetTotal;
import com.yadony.api.payments.currency.SupportedCurrency;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Prix d'un supplément (plan §1.3-b) : l'expéditeur ne paie que la DIFFÉRENCE.
 * <ul>
 *   <li>Bid à grille : {@code commission = C(N + S) − C(N)}, sur la grille FIGÉE du bid et avec
 *       ses réductions sans code promo (taux spécial, bon de parrainage). Sans promo,
 *       {@code N ↦ N + C(N)} est croissante : la différence est positive et le minimum de la devise
 *       n'est jamais facturé deux fois. {@code N} est le net déjà dû ({@link BidNetTotal}).</li>
 *   <li>Bid hérité (sans grille) : {@code S × taux figé du bid} (sinon résolu pour le couple),
 *       arrondi HALF_UP à l'unité mineure, sans minimum.</li>
 * </ul>
 * Lecture pure : rien n'est consommé (ni bon ni code).
 */
@Component
public class BidSupplementPricing {

    private final CommissionGridService gridService;
    private final CommissionRateResolver rateResolver;
    private final BidGridItemRepository gridItems;

    public BidSupplementPricing(CommissionGridService gridService, CommissionRateResolver rateResolver,
                                BidGridItemRepository gridItems) {
        this.gridService = gridService;
        this.rateResolver = rateResolver;
        this.gridItems = gridItems;
    }

    /**
     * @param supplementNet net demandé, ramené à l'unité mineure de la devise du colis.
     * @throws IllegalArgumentException si ce net est nul ou négatif à l'unité mineure.
     */
    public BidSupplementQuote quote(BidEntity bid, AnnouncementEntity announcement, BigDecimal supplementNet) {
        SupportedCurrency currency = SupportedCurrency.fromCodeOrDefault(bid.getCurrency());
        BigDecimal s = supplementNet.setScale(currency.minorUnit(), RoundingMode.HALF_UP);
        if (s.signum() <= 0) {
            throw new IllegalArgumentException("Le net du supplément doit être strictement positif");
        }
        BigDecimal commission = bid.getCommissionGridId() != null
                ? gridCommission(bid, announcement, s, currency)
                : legacyCommission(bid, announcement, s, currency);
        BigDecimal rate = CommissionResult.storableRate(
                commission.divide(s, CommissionResult.STORED_RATE_SCALE, RoundingMode.HALF_UP));
        return new BidSupplementQuote(s, commission, s.add(commission), rate, bid.getCommissionGridId());
    }

    private BigDecimal gridCommission(BidEntity bid, AnnouncementEntity announcement, BigDecimal s,
                                      SupportedCurrency currency) {
        CommissionGrid grid = gridService.require(bid.getCommissionGridId());
        CommissionReductions reductions = bid.getNegotiatedNetEur() != null
                ? CommissionReductions.frozen(bid.getCommissionOverrideRate(), bid.getCommissionVoucherFactor())
                : rateResolver.reductions(announcement.getTravelerId(), bid.getSenderId(), null, null, bid.getId());
        BigDecimal n = BidNetTotal.compute(bid, announcement,
                        bid.getNegotiatedNetEur() != null ? List.of() : gridItems.findByBidId(bid.getId()))
                .setScale(currency.minorUnit(), RoundingMode.HALF_UP);
        BigDecimal before = commissionOf(n, currency, grid, reductions);
        BigDecimal after = commissionOf(n.add(s), currency, grid, reductions);
        return after.subtract(before).max(BigDecimal.ZERO.setScale(currency.minorUnit()));
    }

    private static BigDecimal commissionOf(BigDecimal net, SupportedCurrency currency, CommissionGrid grid,
                                           CommissionReductions reductions) {
        if (net.signum() <= 0) {
            return BigDecimal.ZERO.setScale(currency.minorUnit());
        }
        return CommissionCalculator.compute(net, currency, grid, reductions).commission();
    }

    private BigDecimal legacyCommission(BidEntity bid, AnnouncementEntity announcement, BigDecimal s,
                                        SupportedCurrency currency) {
        BigDecimal rate = bid.getCommissionRate() != null
                ? bid.getCommissionRate()
                : rateResolver.resolve(announcement.getTravelerId(), bid.getSenderId());
        return s.multiply(rate).setScale(currency.minorUnit(), RoundingMode.HALF_UP);
    }
}
