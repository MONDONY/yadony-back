package com.yadony.api.payments.amendment;

import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidGridItemRepository;
import com.yadony.api.matching.BidNetTotal;
import com.yadony.api.payments.supplement.BidSupplementRepository;
import com.yadony.api.payments.supplement.BidSupplementStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Net déjà payé en plus du principal d'un colis : base de la commission différentielle de tout
 * nouvel ajout ({@link com.yadony.api.common.commission.IncrementalCommission}).
 *
 * <p>Somme du net du supplément de prix payé et non remboursé ({@link BidSupplementStatus#PAID_STATES})
 * et du net des modifications de colis acquises ({@link BidAmendmentStatus#SECURED} : acceptées, en
 * cours de versement ou versées). Une modification close après acceptation ou remboursée n'est pas comptée.
 */
@Component
public class BidExtrasNet {

    private final BidSupplementRepository supplements;
    private final BidAmendmentRepository amendments;
    private final BidGridItemRepository gridItems;

    public BidExtrasNet(BidSupplementRepository supplements, BidAmendmentRepository amendments,
                        BidGridItemRepository gridItems) {
        this.supplements = supplements;
        this.amendments = amendments;
        this.gridItems = gridItems;
    }

    /**
     * Net déjà dû sur le colis : {@link BidNetTotal} plus {@link #paidExtrasNet}. Point d'entrée unique de la
     * base de la commission différentielle (supplément et modification). Un colis négocié ne recharge pas
     * ses articles de grille : le prix négocié les couvre déjà, les recharger les compterait deux fois.
     */
    public BigDecimal dueNet(BidEntity bid, AnnouncementEntity announcement) {
        return BidNetTotal.compute(bid, announcement,
                        bid.getNegotiatedNetEur() != null ? List.of() : gridItems.findByBidId(bid.getId()))
                .add(paidExtrasNet(bid.getId()));
    }

    /** Somme des nets payés et non remboursés du colis, 0 s'il n'y en a aucun. */
    public BigDecimal paidExtrasNet(UUID bidId) {
        return supplements.sumNetByBidIdAndStatusIn(bidId, BidSupplementStatus.PAID_STATES)
                .add(amendments.sumNetByBidIdAndStatusIn(bidId, BidAmendmentStatus.SECURED));
    }
}
