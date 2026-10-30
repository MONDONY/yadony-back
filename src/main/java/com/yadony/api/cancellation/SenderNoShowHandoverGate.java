package com.yadony.api.cancellation;

import com.yadony.api.matching.BidStatus;
import com.yadony.api.tracking.HandoverBlocker;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Tant que le voyageur a déclaré l'expéditeur absent ({@code SENDER_NO_SHOW}, portée HANDOVER) et
 * que cette déclaration n'est pas levée, la remise (scan DEPART) est refusée en 409 {@value #CODE}.
 *
 * <p>Sans cette porte, l'expéditeur arrivé en retard remettait son colis, puis l'échéance de
 * contestation ({@code NoShowContestationTimeoutJob}) confirmait l'absence : séquestre remboursé à
 * l'expéditeur et compteur d'incidents incrémenté, alors que le colis voyageait.
 *
 * <ul>
 *   <li>{@code PENDING_CONFIRMATION} (l'expéditeur peut encore contester) et {@code CONTESTED}
 *       (litige ouvert, l'admin tranche) bloquent : la déclaration est ouverte ;</li>
 *   <li>{@code CONFIRMED} bloque aussi : le remboursement est parti, le colis ne doit pas partir.
 *       État normalement inatteignable sur un colis ACCEPTED (la confirmation l'annule) ;</li>
 *   <li>{@code RESOLVED} (déclaration rejetée par l'admin ou litige tranché) ne bloque plus.</li>
 * </ul>
 * Consultée en premier ({@link Ordered#HIGHEST_PRECEDENCE}) : sans effet de bord, et la raison la
 * plus forte de ne pas remettre le colis. La déclaration se crée sous le même verrou du colis
 * ({@link CancellationService#reportSenderNoShow}) : aucune course avec le scan.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SenderNoShowHandoverGate implements HandoverBlocker {

    public static final String CODE = "handover-sender-noshow-pending";
    static final String MESSAGE_KEY = "problem." + CODE;

    /** Action d'audit : déclaration close sans effet parce que le colis a déjà été remis. */
    public static final String AUDIT_CLOSED_PARCEL_HANDED_OVER = "SENDER_NO_SHOW_CLOSED_PARCEL_HANDED_OVER";

    private static final Set<CancellationStatus> NOT_LIFTED = EnumSet.of(
            CancellationStatus.PENDING_CONFIRMATION, CancellationStatus.CONTESTED, CancellationStatus.CONFIRMED);

    /**
     * Statuts où l'expéditeur s'est forcément présenté : colis remis (HANDED_OVER, IN_TRANSIT,
     * ARRIVED, COMPLETED) ou refusé à l'inspection de la remise (PARCEL_REFUSED). Une absence n'y a
     * plus de sens : ni remboursement « absence », ni pénalité.
     */
    private static final Set<BidStatus> SENDER_SHOWED_UP = EnumSet.of(
            BidStatus.HANDED_OVER, BidStatus.IN_TRANSIT, BidStatus.ARRIVED, BidStatus.COMPLETED,
            BidStatus.PARCEL_REFUSED);

    private final CancellationRepository cancellations;

    public SenderNoShowHandoverGate(CancellationRepository cancellations) {
        this.cancellations = cancellations;
    }

    /** L'absence de l'expéditeur n'a plus de sens : il a remis son colis (ou l'a présenté). */
    public static boolean parcelAlreadyHandedOver(BidStatus status) {
        return status != null && SENDER_SHOWED_UP.contains(status);
    }

    @Override
    public Optional<String> blockingCode(UUID bidId) {
        return cancellations.findByBidId(bidId)
                .filter(c -> CancellationReason.SENDER_NO_SHOW.name().equals(c.getReason()))
                .filter(c -> NOT_LIFTED.contains(c.getNoShowStatus()))
                .map(c -> CODE);
    }

    @Override
    public String messageKey() {
        return MESSAGE_KEY;
    }
}
