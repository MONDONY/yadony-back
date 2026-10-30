package com.yadony.api.cancellation;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.cancellation.events.CancellationConfirmedEvent;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.common.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Réputation expéditeur sur no-show confirmé (D6/D8).
 *
 * <p>Quand un sender no-show est confirmé (admin ou timeout de contestation), le bid
 * publie {@link CancellationConfirmedEvent} avec {@code reason = SENDER_NO_SHOW}. On
 * incrémente alors le compteur d'incidents de remise de l'expéditeur. L'incrément n'a
 * lieu qu'au statut CONFIRMED (D8 anti-farming) puisque l'event n'est publié qu'à ce
 * moment.
 *
 * <p>AFTER_COMMIT + REQUIRES_NEW : la mutation de réputation est durable dans sa propre
 * transaction, indépendante du remboursement (cf. SenderNoShowConfirmedListener).
 */
@Component
public class SenderReputationListener {

    private static final Logger log = LoggerFactory.getLogger(SenderReputationListener.class);

    private final BidRepository bidRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public SenderReputationListener(BidRepository bidRepository,
                                    UserRepository userRepository,
                                    AuditService auditService) {
        this.bidRepository = bidRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCancellationConfirmed(CancellationConfirmedEvent event) {
        if (event.reason() != CancellationReason.SENDER_NO_SHOW) {
            return;
        }
        BidEntity bid = bidRepository.findById(event.bidId()).orElse(null);
        if (bid == null) {
            log.debug("Sender no-show réputation : bid {} introuvable", event.bidId());
            return;
        }
        // Défense en profondeur : colis remis, l'expéditeur était là, aucune pénalité.
        if (SenderNoShowHandoverGate.parcelAlreadyHandedOver(bid.getStatus())) {
            log.warn("Sender no-show réputation : colis {} déjà {}, aucun incident compté", bid.getId(), bid.getStatus());
            return;
        }
        UserEntity sender = userRepository.findById(bid.getSenderId()).orElse(null);
        if (sender == null) {
            log.debug("Sender no-show réputation : expéditeur {} introuvable", bid.getSenderId());
            return;
        }
        int newCount = sender.getSenderHandoverIncidentCount() + 1;
        sender.setSenderHandoverIncidentCount(newCount);
        userRepository.save(sender);

        auditService.log("USER", sender.getId(), "SENDER_REPUTATION_INCIDENT", null,
                Map.of("bidId", event.bidId().toString(),
                       "reason", "sender_no_show",
                       "count", newCount));
        log.info("Réputation expéditeur {} : incident no-show confirmé (count={})",
                sender.getId(), newCount);
    }
}
