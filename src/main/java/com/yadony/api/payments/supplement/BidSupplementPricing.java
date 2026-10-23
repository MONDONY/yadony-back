package com.yadony.api.payments.supplement;

import com.yadony.api.common.commission.IncrementalCommission;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.payments.amendment.BidExtrasNet;
import com.yadony.api.payments.currency.SupportedCurrency;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Prix d'un supplément (plan §1.3-b) : l'expéditeur ne paie que la DIFFÉRENCE de commission, calculée
 * par {@link IncrementalCommission} (partagée avec la modification d'un colis accepté) sur le net déjà
 * dû : {@link BidExtrasNet#dueNet} (principal plus ajouts déjà payés).
 * Lecture pure : rien n'est consommé (ni bon ni code).
 */
@Component
public class BidSupplementPricing {

    private final IncrementalCommission incrementalCommission;
    private final BidExtrasNet extrasNet;

    public BidSupplementPricing(IncrementalCommission incrementalCommission, BidExtrasNet extrasNet) {
        this.incrementalCommission = incrementalCommission;
        this.extrasNet = extrasNet;
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
        IncrementalCommission.Result r = incrementalCommission.compute(bid, announcement.getTravelerId(),
                extrasNet.dueNet(bid, announcement), s);
        return new BidSupplementQuote(s, r.commission(), s.add(r.commission()), r.rateSnapshot(), r.gridId());
    }
}
