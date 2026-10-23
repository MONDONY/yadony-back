package com.yadony.api.payments.amendment;

import com.yadony.api.payments.supplement.BidSupplementRepository;
import com.yadony.api.payments.supplement.BidSupplementStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Net déjà payé en plus du principal d'un colis : base de la commission différentielle de tout
 * nouvel ajout ({@link com.yadony.api.common.commission.IncrementalCommission}).
 *
 * <p>Aujourd'hui, seulement le net du supplément de prix payé et non remboursé
 * ({@link BidSupplementStatus#PAID_STATES}). Les modifications de colis acceptées s'y ajoutent avec
 * leur table ; la signature reste stable.
 */
@Component
public class BidExtrasNet {

    private final BidSupplementRepository supplements;

    public BidExtrasNet(BidSupplementRepository supplements) {
        this.supplements = supplements;
    }

    /** Somme des nets payés et non remboursés du colis, 0 s'il n'y en a aucun. */
    public BigDecimal paidExtrasNet(UUID bidId) {
        return supplements.sumNetByBidIdAndStatusIn(bidId, BidSupplementStatus.PAID_STATES);
    }
}
