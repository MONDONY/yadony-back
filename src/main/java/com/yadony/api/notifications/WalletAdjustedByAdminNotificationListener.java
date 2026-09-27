package com.yadony.api.notifications;

import com.yadony.api.common.i18n.Messages;
import com.yadony.api.payments.wallet.WalletAdjustedByAdminEvent;
import com.yadony.api.payments.wallet.WalletAdjustmentDirection;
import com.yadony.api.payments.wallet.WalletAmountText;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Prévient l'utilisateur qu'un admin a corrigé son solde : notification in-app et push,
 * dans la langue du destinataire, sans le motif interne. AFTER_COMMIT : une correction
 * annulée ne doit rien annoncer.
 */
@Component
public class WalletAdjustedByAdminNotificationListener {

    static final String TYPE = "WALLET_ADJUSTED";

    private final NotificationDispatcher notificationDispatcher;

    public WalletAdjustedByAdminNotificationListener(NotificationDispatcher notificationDispatcher) {
        this.notificationDispatcher = notificationDispatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onWalletAdjusted(WalletAdjustedByAdminEvent event) {
        Messages m = notificationDispatcher.messagesFor(event.userId());
        NotificationText text = NotificationTexts.walletAdjustedByAdmin(m,
                event.direction() == WalletAdjustmentDirection.CREDIT,
                WalletAmountText.format(event.amount(), event.currency()));
        notificationDispatcher.notifyUser(event.userId(), text.title(), text.body(),
                Map.of("type", TYPE,
                        "currency", event.currency(),
                        "transactionId", event.transactionId().toString()));
    }
}
