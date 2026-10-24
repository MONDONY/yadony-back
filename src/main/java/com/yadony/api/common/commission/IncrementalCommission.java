package com.yadony.api.common.commission;

import com.yadony.api.common.CommissionRateResolver;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.payments.currency.SupportedCurrency;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Commission différentielle d'un ajout sur un colis déjà tarifé (supplément de prix, modification
 * d'un colis accepté) : l'expéditeur ne paie que la DIFFÉRENCE.
 * <ul>
 *   <li>Bid à grille : {@code commission = C(base + extra) − C(base)}, sur la grille FIGÉE du bid et
 *       avec ses réductions sans code promo (figées sur le bid s'il est négocié, sinon taux spécial
 *       et bon de parrainage). Sans promo, {@code N ↦ N + C(N)} est croissante : la différence est
 *       positive et le minimum de la devise n'est jamais facturé deux fois. Plancher à 0.</li>
 *   <li>Bid hérité (sans grille) : {@code extra × taux figé du bid} (sinon résolu pour le couple),
 *       arrondi HALF_UP à l'unité mineure, sans minimum.</li>
 * </ul>
 * Lecture pure : rien n'est consommé (ni bon ni code).
 */
@Component
public class IncrementalCommission {

    /**
     * @param commission   commission de l'ajout, à l'unité mineure de la devise du colis.
     * @param rateSnapshot taux effectif {@code commission / extra}, borné comme les autres taux enregistrés.
     * @param gridId       grille figée du bid, null pour un bid hérité.
     */
    public record Result(BigDecimal commission, BigDecimal rateSnapshot, UUID gridId) {
    }

    private final CommissionGridService gridService;
    private final CommissionRateResolver rateResolver;

    public IncrementalCommission(CommissionGridService gridService, CommissionRateResolver rateResolver) {
        this.gridService = gridService;
        this.rateResolver = rateResolver;
    }

    /**
     * @param travelerId voyageur du trajet du colis (taux spécial et bon de parrainage du couple).
     * @param baseNet    net déjà dû sur le colis (principal et ajouts déjà payés).
     * @param extraNet   net ajouté, ramené à l'unité mineure de la devise du colis.
     * @throws IllegalArgumentException si ce net est nul ou négatif à l'unité mineure.
     */
    public Result compute(BidEntity bid, UUID travelerId, BigDecimal baseNet, BigDecimal extraNet) {
        SupportedCurrency currency = requirePositive(bid, extraNet);
        BigDecimal extra = extraNet.setScale(currency.minorUnit(), RoundingMode.HALF_UP);
        BigDecimal base = baseNet.setScale(currency.minorUnit(), RoundingMode.HALF_UP);
        BigDecimal commission = bid.getCommissionGridId() != null
                ? gridCommission(bid, travelerId, base, extra, currency)
                : legacyCommission(bid, travelerId, extra, currency);
        BigDecimal rate = CommissionResult.storableRate(
                commission.divide(extra, CommissionResult.STORED_RATE_SCALE, RoundingMode.HALF_UP));
        return new Result(commission, rate, bid.getCommissionGridId());
    }

    private static SupportedCurrency requirePositive(BidEntity bid, BigDecimal extraNet) {
        SupportedCurrency currency = SupportedCurrency.fromCodeOrDefault(bid.getCurrency());
        if (extraNet.setScale(currency.minorUnit(), RoundingMode.HALF_UP).signum() <= 0) {
            throw new IllegalArgumentException("Le net ajouté doit être strictement positif");
        }
        return currency;
    }

    private BigDecimal gridCommission(BidEntity bid, UUID travelerId, BigDecimal base, BigDecimal extra,
                                      SupportedCurrency currency) {
        CommissionGrid grid = gridService.require(bid.getCommissionGridId());
        CommissionReductions reductions = bid.getNegotiatedNetEur() != null
                ? CommissionReductions.frozen(bid.getCommissionOverrideRate(), bid.getCommissionVoucherFactor())
                : rateResolver.reductions(travelerId, bid.getSenderId(), null, null, bid.getId());
        BigDecimal before = commissionOf(base, currency, grid, reductions);
        BigDecimal after = commissionOf(base.add(extra), currency, grid, reductions);
        return after.subtract(before).max(BigDecimal.ZERO.setScale(currency.minorUnit()));
    }

    private static BigDecimal commissionOf(BigDecimal net, SupportedCurrency currency, CommissionGrid grid,
                                           CommissionReductions reductions) {
        if (net.signum() <= 0) {
            return BigDecimal.ZERO.setScale(currency.minorUnit());
        }
        return CommissionCalculator.compute(net, currency, grid, reductions).commission();
    }

    private BigDecimal legacyCommission(BidEntity bid, UUID travelerId, BigDecimal extra,
                                        SupportedCurrency currency) {
        BigDecimal rate = bid.getCommissionRate() != null
                ? bid.getCommissionRate()
                : rateResolver.resolve(travelerId, bid.getSenderId());
        return extra.multiply(rate).setScale(currency.minorUnit(), RoundingMode.HALF_UP);
    }
}
