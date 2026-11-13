package com.yadony.api.payments;

import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.PayoutTrigger;

import java.util.Set;

/**
 * Colis payé au dépôt ({@link PayoutTrigger#DEPOSIT}, figé à l'acceptation) : quand son séquestre devient-il versable ?
 * Une seule définition, partagée par le versement au dépôt ({@link DeliveryEventListener}) et par les rattrapages
 * ({@link DeliveredEscrowReleaser} : séquestre tardif, resynchronisation, auto-réparation).
 *
 * <p>Versable = colis {@code DEPOSIT} dont l'étape DEPART est enregistrée, et qui est encore dans le parcours (déposé,
 * en route, arrivé ou livré). Un colis annulé, refusé ou déclaré absent après le dépôt n'est jamais versé par
 * l'automate : l'argent qui revient à l'expéditeur, ou la décision de l'équipe, l'emporte. Un colis
 * {@link PayoutTrigger#DELIVERY} n'est jamais versable au dépôt.
 */
final class DepositRelease {

    /** Statuts d'un colis déposé et toujours dans le parcours : {@link PayoutTrigger#DEPOSITED_STATUSES}. */
    static final Set<BidStatus> DEPOSITED_STATUSES = PayoutTrigger.DEPOSITED_STATUSES;

    private DepositRelease() {
    }

    /** Colis payé au dépôt (règle figée à l'acceptation). */
    static boolean paidAtDeposit(BidEntity bid) {
        return bid != null && bid.getPayoutTrigger() == PayoutTrigger.DEPOSIT;
    }

    /** Colis payé au dépôt, déposé (DEPART enregistré) et toujours dans le parcours : son séquestre est versable. */
    static boolean depositedAndPayable(BidEntity bid, BidRepository bids) {
        return paidAtDeposit(bid)
                && bid.getId() != null
                && DEPOSITED_STATUSES.contains(bid.getStatus())
                && bids.findDepartScannedAt(bid.getId()) != null;
    }
}
