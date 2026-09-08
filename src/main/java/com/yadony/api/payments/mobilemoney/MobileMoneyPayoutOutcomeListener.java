package com.yadony.api.payments.mobilemoney;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.events.PaymentReleasedEvent;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayText;
import com.yadony.api.payments.pawapay.events.PawapayOperationCompletedEvent;
import com.yadony.api.payments.pawapay.events.PawapayOperationFailedEvent;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Écoute {@link PawapayOperationCompletedEvent} / {@link PawapayOperationFailedEvent} pour le
 * seul {@code kind == PAYOUT} (les deux événements sont génériques à toutes les opérations
 * pawaPay — deposit, payout, refund — {@link MobileMoneyDepositOutcomeListener} écoute déjà
 * les mêmes événements pour {@code DEPOSIT}).
 *
 * <p>Payout {@code COMPLETED} : pawaPay confirme que l'argent est réellement arrivé sur le
 * mobile money du voyageur — c'est SEULEMENT à ce moment que {@link PaymentReleasedEvent} est
 * publié (jamais à la simple soumission/acceptation, qui ne garantit pas encore l'arrivée des
 * fonds), pour notifier le voyageur dans sa devise.
 *
 * <p>Payout {@code FAILED} : l'argent n'est PAS parti (pawaPay ne débite le solde yadony qu'à
 * la confirmation), mais le paiement reste {@code RELEASED} — {@code MobileMoneyPayoutInitiator}
 * a déjà validé le claim avant la soumission, et rien ici ne le fait revenir en {@code ESCROW}
 * automatiquement : ce cas est anormal (pawaPay a ACCEPTED puis FAILED un payout dont les
 * comptes/montants ont été validés à la soumission) et attend une reprise par un administrateur
 * (relance admin), d'où l'alerte {@code PAWAPAY_PAYOUT_FAILED} plutôt qu'une
 * correction automatique.
 *
 * <p><b>Règle 18 du projet, non négociable</b> — {@code @TransactionalEventListener(phase =
 * AFTER_COMMIT)} combiné à {@code @Transactional(propagation = REQUIRES_NEW)} sur les deux
 * méthodes, jamais un {@code @EventListener} seul : les deux événements sont publiés À
 * L'INTÉRIEUR de la transaction qui applique la transition ({@code PawapayOperationService#apply}),
 * un écouteur simple lirait donc des données pas encore visibles des autres connexions (et
 * potentiellement jamais commitées si cette transaction est finalement annulée). Les deux
 * méthodes sont enregistrées dans {@code PaymentListenerTransactionalContractTest#fullContractListeners()}.
 */
@Component
public class MobileMoneyPayoutOutcomeListener {

    private static final Logger log = LoggerFactory.getLogger(MobileMoneyPayoutOutcomeListener.class);

    private final PawapayOperationService operations;
    private final PaymentRepository paymentRepository;
    private final BidRepository bidRepository;
    private final AnnouncementRepository announcementRepository;
    private final ApplicationEventPublisher events;
    private final AdminAlertService adminAlert;
    private final AuditService audit;

    public MobileMoneyPayoutOutcomeListener(PawapayOperationService operations, PaymentRepository paymentRepository,
                                            BidRepository bidRepository, AnnouncementRepository announcementRepository,
                                            ApplicationEventPublisher events, AdminAlertService adminAlert,
                                            AuditService audit) {
        this.operations = operations;
        this.paymentRepository = paymentRepository;
        this.bidRepository = bidRepository;
        this.announcementRepository = announcementRepository;
        this.events = events;
        this.adminAlert = adminAlert;
        this.audit = audit;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCompleted(PawapayOperationCompletedEvent event) {
        if (event.kind() != PawapayOperationKind.PAYOUT || event.paymentId() == null) return;
        PawapayOperationEntity op = operations.get(event.operationId());
        // Cas structurellement impossible aujourd'hui (le paiement, son bid
        // et son annonce existent nécessairement pour avoir pu être versés), mais un log.warn
        // coûte une ligne — même garde-fou que MobileMoneyBidPaymentService#notifyDepositFailed
        // pour son cas jumeau, plutôt qu'un silence total si l'invariant venait à se rompre.
        var payment = paymentRepository.findById(event.paymentId());
        if (payment.isEmpty()) {
            log.warn("Payout {} COMPLETED mais paiement {} introuvable, notification abandonnée",
                    op.getId(), event.paymentId());
            return;
        }
        var bid = bidRepository.findById(payment.get().getBidId());
        if (bid.isEmpty()) {
            log.warn("Payout {} COMPLETED : bid {} introuvable (paiement {}), notification abandonnée",
                    op.getId(), payment.get().getBidId(), event.paymentId());
            return;
        }
        var announcement = announcementRepository.findById(bid.get().getAnnouncementId());
        if (announcement.isEmpty()) {
            log.warn("Payout {} COMPLETED : annonce {} introuvable (bid {}), notification abandonnée",
                    op.getId(), bid.get().getAnnouncementId(), bid.get().getId());
            return;
        }
        audit.log("PAYMENT", payment.get().getId(), "MM_PAYOUT_COMPLETED", bid.get().getSenderId(),
                Map.of("operationId", op.getId().toString(), "net", op.getAmount().toPlainString()));
        events.publishEvent(new PaymentReleasedEvent(bid.get().getId(), announcement.get().getTravelerId(),
                bid.get().getSenderId(), op.getAmount(), op.getCurrency(), true));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onFailed(PawapayOperationFailedEvent event) {
        if (event.kind() != PawapayOperationKind.PAYOUT || event.paymentId() == null) return;
        // Bornés avant audit, journal ou Telegram : failureMessage est TEXT en base (non borné),
        // failureCode l'est déjà à 64 par sa colonne — même garde ici, par défense en profondeur.
        String failureCode = PawapayText.clamp(event.failureCode());
        String failureMessage = PawapayText.clamp(event.failureMessage());
        audit.log("PAYMENT", event.paymentId(), "MM_PAYOUT_FAILED", null,
                Map.of("operationId", event.operationId().toString(), "failureCode", String.valueOf(failureCode)));
        adminAlert.raise("PAWAPAY_PAYOUT_FAILED",
                "Payout mobile money échoué pour le paiement " + event.paymentId() + " : " + failureCode,
                Map.of("paymentId", event.paymentId().toString(), "operationId", event.operationId().toString(),
                        "failureCode", String.valueOf(failureCode), "failureMessage", String.valueOf(failureMessage)));
    }
}
