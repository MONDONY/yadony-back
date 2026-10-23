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
 * <p>Somme du net du supplément de prix payé et non remboursé ({@link BidSupplementStatus#PAID_STATES})
 * et du net des modifications de colis acceptées et non remboursées (acceptées, donc
 * {@code acceptedAt} renseigné, et ni {@code REFUND_PENDING} ni {@code REFUNDED}).
 */
@Component
public class BidExtrasNet {

    private final BidSupplementRepository supplements;
    private final BidAmendmentRepository amendments;

    public BidExtrasNet(BidSupplementRepository supplements, BidAmendmentRepository amendments) {
        this.supplements = supplements;
        this.amendments = amendments;
    }

    /** Somme des nets payés et non remboursés du colis, 0 s'il n'y en a aucun. */
    public BigDecimal paidExtrasNet(UUID bidId) {
        return supplements.sumNetByBidIdAndStatusIn(bidId, BidSupplementStatus.PAID_STATES)
                .add(amendments.sumAcceptedNetByBidIdAndStatusNotIn(bidId, BidAmendmentStatus.REFUNDED_STATES));
    }
}
