package com.yadony.api.payments.bankpayout;

import com.yadony.api.payments.events.StripeTransferRecordedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Ouvre le suivi du virement bancaire dès que le Transfer de la livraison est enregistré. Après
 * commit (un versement annulé n'ouvre rien) et hors du fil du versement : les lectures Stripe ne
 * retardent pas la livraison, et un échec est repris par {@link BankPayoutSyncJob}.
 */
@Component
public class BankPayoutTransferListener {

    private static final Logger log = LoggerFactory.getLogger(BankPayoutTransferListener.class);

    private final BankPayoutTrackingService service;

    public BankPayoutTransferListener(BankPayoutTrackingService service) {
        this.service = service;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onTransferRecorded(StripeTransferRecordedEvent event) {
        try {
            service.recordTransfer(event);
        } catch (RuntimeException e) {
            log.warn("Suivi virement du paiement {} non ouvert ({}) : le rattrapage reprendra",
                    event.paymentId(), e.getMessage());
        }
    }
}
