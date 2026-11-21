package com.yadony.api.cancellation.fee;

import com.yadony.api.common.AuditService;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.events.BidCancelledByParticipantEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Enregistre la retenue due par un expéditeur fautif (ligne {@code DUE} de {@code cancellation_fees}),
 * au moment même de l'annulation. Le paquet {@code payments} la lit au remboursement
 * ({@link CancellationFeeRatePortAdapter}) et la règle.
 *
 * <p>Trois fautes de l'expéditeur, colis payé par carte ou mobile money, et elles seules :
 * <ul>
 *   <li>il annule un colis accepté ou déjà remis ({@code BidService.cancelBid}, écouté ici sur
 *       {@link BidCancelledByParticipantEvent}) ;</li>
 *   <li>son absence à la remise est confirmée (par lui, par l'équipe ou à l'échéance de
 *       contestation : {@code SenderNoShowBidCancelListener}, au passage du colis en annulé) ;</li>
 *   <li>il annule après la remise ({@code CancellationService.cancelAfterHandover}, appel direct).</li>
 * </ul>
 * Jamais pour le voyageur (annulation, trajet, absence), un colis pas encore accepté
 * ({@code PAYMENT_ESCROWED}), les espèces, l'annulation avant paiement, l'annulation par l'équipe, le
 * retrait après report du trajet, la suppression de compte : aucun de ces chemins n'appelle ce composant.
 *
 * <p><b>Même transaction que l'annulation (MANDATORY), écouteur synchrone</b> : le remboursement part
 * après le commit, sur un fil asynchrone ({@code BidRejectedEventListener},
 * {@code SenderNoShowConfirmedListener}, {@code TripCancelledEventListener}, tous {@code AFTER_COMMIT}),
 * et doit trouver la ligne pour ne rembourser que le net. Un {@code @TransactionalEventListener}
 * l'écrirait après ce commit, en course avec le remboursement ; une transaction à part la laisserait
 * survivre à une annulation défaite. Écrite avant le commit, dans la transaction qui annule, elle existe
 * dès que le remboursement peut partir et disparaît si l'annulation échoue.
 *
 * <p><b>Interrupteur et date d'effet</b> : aucune ligne n'est écrite si {@link CancellationFeeEligibility} ne couvre
 * pas le colis (frais éteints, date d'effet absente, colis créé avant elle, espèces). C'est la seule décision, partagée
 * avec l'aperçu montré à l'expéditeur. Une ligne déjà {@code DUE} quand on éteint l'interrupteur garde son cycle :
 * le règlement et le versement ne relisent pas l'interrupteur, l'argent promis au voyageur doit arriver.
 *
 * <p>Idempotent : une seule ligne par colis (index unique V393) ; un second appel est sans effet. Les
 * trois appelants tiennent le verrou du colis. Un colis ou un trajet introuvable n'empêche pas
 * l'annulation : aucune retenue (en faveur de l'expéditeur).
 */
@Component
public class SenderCancellationFeeRecorder {

    private static final Logger log = LoggerFactory.getLogger(SenderCancellationFeeRecorder.class);

    static final String AUDIT_FEE_DUE = "CANCELLATION_FEE_DUE";

    private final CancellationFeeRepository fees;
    private final BidRepository bids;
    private final CancellationFeeEligibility eligibility;
    private final AuditService auditService;
    private final Clock clock;

    public SenderCancellationFeeRecorder(CancellationFeeRepository fees, BidRepository bids,
                                         CancellationFeeEligibility eligibility, AuditService auditService,
                                         Clock clock) {
        this.fees = fees;
        this.bids = bids;
        this.eligibility = eligibility;
        this.auditService = auditService;
        this.clock = clock;
    }

    /**
     * Annulation à l'unité par l'expéditeur d'un colis que le voyageur avait accepté (ACCEPTED) ou déjà
     * pris en main (HANDED_OVER, encore annulable avant le départ). Un colis seulement payé
     * ({@code PAYMENT_ESCROWED}), en attente de paiement ou de réponse n'engageait pas encore le voyageur.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onBidCancelledByParticipant(BidCancelledByParticipantEvent event) {
        if (event.byTraveler()) {
            return;
        }
        senderCancellationReason(event.previousStatus()).ifPresent(reason -> record(event.bidId(), reason));
    }

    /**
     * Motif de la retenue quand l'expéditeur annule un colis depuis ce statut : accepté ({@code SENDER_CANCEL}) ou déjà
     * remis avant le départ ({@code SENDER_CANCEL_AFTER_HANDOVER}) ; vide pour tout autre statut (pas encore accepté,
     * en route, terminé). Partagé avec l'aperçu montré à l'expéditeur ({@link CancellationFeePreviewService}).
     */
    static Optional<CancellationFeeReason> senderCancellationReason(BidStatus status) {
        if (status == BidStatus.ACCEPTED) {
            return Optional.of(CancellationFeeReason.SENDER_CANCEL);
        }
        if (status == BidStatus.HANDED_OVER) {
            return Optional.of(CancellationFeeReason.SENDER_CANCEL_AFTER_HANDOVER);
        }
        return Optional.empty();
    }

    /**
     * Crée la ligne {@code DUE} au taux du barème ({@link CancellationFeeTiers}, temps restant avant le
     * départ à cet instant). Sans effet si une ligne existe déjà, si le colis n'est pas couvert
     * ({@link CancellationFeeEligibility#assess} : frais éteints ou sans date d'effet, colis créé avant elle, ni carte
     * ni mobile money), ou si le taux est nul (annulation assez tôt). Les montants restent à zéro jusqu'au
     * règlement par le paquet {@code payments} ({@link CancellationFeeRatePortAdapter#markRetained}) :
     * seul ce dernier connaît le montant payé, les frais et la commission.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID bidId, CancellationFeeReason reason) {
        if (fees.findByBidId(bidId).isPresent()) {
            return;
        }
        Optional<BidEntity> bid = bids.findById(bidId);
        if (bid.isEmpty()) {
            return;
        }
        Optional<CancellationFeeEligibility.Assessment> assessed = eligibility.assess(bid.get(), Instant.now(clock));
        if (assessed.isEmpty() || assessed.get().rate().signum() <= 0) {
            return;
        }
        AnnouncementEntity trip = assessed.get().trip();
        BigDecimal rate = assessed.get().rate();

        CancellationFeeEntity fee = new CancellationFeeEntity();
        fee.setBidId(bidId);
        fee.setSenderId(bid.get().getSenderId());
        fee.setTravelerId(trip.getTravelerId());
        fee.setRate(rate);
        fee.setReason(reason);
        fee.setStatus(CancellationFeeStatus.DUE);
        // Colonnes NOT NULL (V393) : zéro tant que la retenue n'est pas réglée, devise du colis.
        fee.setGrossFee(BigDecimal.ZERO);
        fee.setRefundFees(BigDecimal.ZERO);
        fee.setCommission(BigDecimal.ZERO);
        fee.setTravelerNet(BigDecimal.ZERO);
        fee.setCurrency(bid.get().getCurrency());
        fee = fees.save(fee);

        // Décision de la plateforme (acteur nul) : l'auteur de l'annulation est dans l'audit de celle-ci.
        auditService.log("BID", bidId, AUDIT_FEE_DUE, null, Map.of(
                "cancellationFeeId", String.valueOf(fee.getId()),
                "reason", reason.name(),
                "rate", rate.toPlainString(),
                "senderId", String.valueOf(fee.getSenderId()),
                "travelerId", String.valueOf(fee.getTravelerId())));
        log.info("Retenue d'annulation due sur le colis {} : {} au taux {}", bidId, reason, rate);
    }
}
