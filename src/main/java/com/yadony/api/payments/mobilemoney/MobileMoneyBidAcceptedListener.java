package com.yadony.api.payments.mobilemoney;

import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.events.BidAcceptedEvent;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.payments.cash.PaymentMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

@Component
public class MobileMoneyBidAcceptedListener {

    private static final Logger log = LoggerFactory.getLogger(MobileMoneyBidAcceptedListener.class);

    private final MobileMoneyPaymentService mmPaymentService;
    private final BidRepository bidRepository;
    private final NotificationDispatcher notificationDispatcher;

    public MobileMoneyBidAcceptedListener(MobileMoneyPaymentService mmPaymentService,
                                           BidRepository bidRepository,
                                           NotificationDispatcher notificationDispatcher) {
        this.mmPaymentService      = mmPaymentService;
        this.bidRepository         = bidRepository;
        this.notificationDispatcher = notificationDispatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBidAccepted(BidAcceptedEvent event) {
        BidEntity bid = bidRepository.findById(event.getBidId()).orElse(null);
        if (bid == null) {
            log.warn("MobileMoneyBidAcceptedListener: bid {} not found", event.getBidId());
            return;
        }

        PaymentMethod pm = bid.getPaymentMethod();
        // Même prédicat que celui porté par BidAcceptedEvent.isMobileMoney() : les deux
        // doivent s'accorder, sinon l'expéditeur perd « Demande acceptée ! » sans recevoir
        // « Payez votre trajet » en échange.
        if (pm == null || !pm.isMobileMoney()) {
            return;
        }

        try {
            MobileMoneyPaymentEntity mmPayment = mmPaymentService.initiate(event.getBidId(), bid.getSenderId());
            var text = com.yadony.api.notifications.NotificationTexts.mobileMoneyPaymentPending(pm.name());
            notificationDispatcher.notifyUser(
                    event.getSenderId(),
                    text.title(),
                    text.body(),
                    Map.of("type", "MM_PAYMENT_PENDING",
                           "bidId", event.getBidId().toString(),
                           "paymentLink", mmPayment.getPaymentLink() != null ? mmPayment.getPaymentLink() : "")
            );
            log.info("MobileMoneyBidAcceptedListener: initiated MM payment for bidId={} provider={}",
                    event.getBidId(), pm);
        } catch (Exception e) {
            log.error("MobileMoneyBidAcceptedListener: failed to initiate MM for bidId={}",
                    event.getBidId(), e);
            // Filet indispensable : le push générique « Demande acceptée ! » a été supprimé
            // en amont parce que celui-ci était censé le remplacer. Sans ce repli, un échec
            // d'initiation (passerelle non déployée, gateway injoignable) laisserait
            // l'expéditeur sans AUCUNE notification alors que son colis vient d'être accepté.
            var fallback = com.yadony.api.notifications.NotificationTexts.bidAcceptedPayNow();
            notificationDispatcher.notifyUser(
                    event.getSenderId(),
                    fallback.title(),
                    fallback.body(),
                    Map.of("type", "BID_ACCEPTED", "bidId", event.getBidId().toString())
            );
        }
    }
}
