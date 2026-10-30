package com.yadony.api.cancellation;

import com.yadony.api.cancellation.events.SenderNoShowWithdrawnEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.tracking.HandoverBlocker;
import com.yadony.api.tracking.HandoverScan;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Porte de la remise (scan DEPART) face à une déclaration « expéditeur absent » (HANDOVER,
 * {@code SENDER_NO_SHOW}). Sans elle, l'expéditeur arrivé en retard remettait son colis, puis
 * l'échéance de contestation confirmait l'absence : séquestre remboursé et incident compté alors
 * que le colis voyageait. Décision du propriétaire, selon l'état de la déclaration :
 * <ul>
 *   <li>{@code PENDING_CONFIRMATION} : la remise passe ({@link #blockingCode} ne bloque pas) et,
 *       une fois enregistrée, le scan <b>lève</b> la déclaration ({@link #onHandover}) : clôture en
 *       {@code RESOLVED} dans la transaction du scan, sous le verrou du colis, audit
 *       {@code SENDER_NO_SHOW_WITHDRAWN_BY_HANDOVER} (auteur : le voyageur qui scanne ; étape,
 *       provenance, photo, heure hors ligne et position du scan), {@link SenderNoShowWithdrawnEvent}
 *       pour prévenir l'expéditeur après commit. Si le scan échoue ensuite, la levée est annulée
 *       avec lui. Aucune preuve n'est exigée pour lever (décision du propriétaire) ;</li>
 *   <li>{@code CONTESTED} (litige ouvert, l'équipe tranche) et {@code CONFIRMED} (absence actée,
 *       remboursement parti) : 409 {@value #CODE}, rien n'est modifié ;</li>
 *   <li>{@code RESOLVED} ou aucune déclaration : rien ne bloque.</li>
 * </ul>
 * Consultée en premier ({@link Ordered#HIGHEST_PRECEDENCE}). Toutes les écritures sur la
 * déclaration de remise (déclaration, contestation, confirmations, échéance) prennent d'abord le
 * verrou du colis : aucune course avec le scan, aucun interblocage.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SenderNoShowHandoverGate implements HandoverBlocker {

    public static final String CODE = "handover-sender-noshow-pending";
    static final String MESSAGE_KEY = "problem." + CODE;

    private final CancellationRepository cancellations;
    private final BidRepository bids;
    private final ApplicationEventPublisher events;
    private final SenderNoShowClosing closing;

    public SenderNoShowHandoverGate(CancellationRepository cancellations, BidRepository bids,
                                    AuditService auditService, ApplicationEventPublisher events) {
        this.cancellations = cancellations;
        this.bids = bids;
        this.events = events;
        this.closing = new SenderNoShowClosing(cancellations, auditService);
    }

    /** Lecture seule : seules une déclaration contestée ou confirmée refusent la remise. */
    @Override
    public Optional<String> blockingCode(UUID bidId) {
        return senderNoShow(bidId)
                .filter(c -> c.getNoShowStatus() == CancellationStatus.CONTESTED
                        || c.getNoShowStatus() == CancellationStatus.CONFIRMED)
                .map(c -> CODE);
    }

    /**
     * La remise vient d'être enregistrée : une déclaration encore en attente est levée, avec ce
     * qui prouve la remise dans l'audit (étape, provenance, photo, heure hors ligne, position).
     */
    @Override
    public void onHandover(HandoverScan scan) {
        senderNoShow(scan.bidId())
                .filter(c -> c.getNoShowStatus() == CancellationStatus.PENDING_CONFIRMATION)
                .ifPresent(c -> {
                    closing.withdrawByHandover(c, scan);
                    bids.findById(scan.bidId()).map(BidEntity::getSenderId).ifPresent(senderId ->
                            events.publishEvent(new SenderNoShowWithdrawnEvent(scan.bidId(), c.getId(), senderId)));
                });
    }

    private Optional<CancellationEntity> senderNoShow(UUID bidId) {
        return cancellations.findByBidId(bidId)
                .filter(c -> CancellationReason.SENDER_NO_SHOW.name().equals(c.getReason()));
    }

    @Override
    public String messageKey() {
        return MESSAGE_KEY;
    }
}
