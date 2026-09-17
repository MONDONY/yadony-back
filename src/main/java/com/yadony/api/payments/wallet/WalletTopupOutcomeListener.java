package com.yadony.api.payments.wallet;

import com.yadony.api.common.AuditService;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationPurpose;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayProviders;
import com.yadony.api.payments.pawapay.events.PawapayOperationCompletedEvent;
import com.yadony.api.payments.pawapay.events.PawapayOperationFailedEvent;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Crédite le portefeuille à l'issue d'une recharge mobile money : écoute les événements
 * pawaPay génériques {@link PawapayOperationCompletedEvent} / {@link PawapayOperationFailedEvent}
 * pour le seul cas {@code kind == DEPOSIT} et {@code purpose == WALLET_TOPUP} — les dépôts de
 * paiement de colis ({@code BID_PAYMENT}) sont traités par
 * {@code MobileMoneyDepositOutcomeListener}, jamais ici.
 *
 * <p><b>Règle 18 du projet, non négociable</b> — {@code @TransactionalEventListener(phase =
 * AFTER_COMMIT)} combiné à {@code @Transactional(propagation = REQUIRES_NEW)} sur les deux
 * méthodes, jamais un {@code @EventListener} seul : les deux événements sont publiés À
 * L'INTÉRIEUR de la transaction qui applique la transition
 * ({@code PawapayOperationService#apply}, voir le Javadoc des deux événements). Un écouteur
 * simple lirait une transition pas encore visible des autres connexions, et créditerait un
 * portefeuille pour un dépôt dont la ligne pourrait finalement ne jamais être committée.
 *
 * <p>Le crédit passe par {@link WalletService#credit}, idempotent sur la clé
 * {@code "pawapay-topup-" + depositId} : un rejeu du callback pawaPay (ou de l'événement) ne
 * crédite jamais deux fois. {@code credit} appelle {@code getOrCreate} : le solde dans la
 * devise du dépôt est créé au besoin, il n'a pas à préexister.
 */
@Component
public class WalletTopupOutcomeListener {

    private static final Logger log = LoggerFactory.getLogger(WalletTopupOutcomeListener.class);

    private final PawapayOperationService operations;
    private final WalletService walletService;
    private final AuditService auditService;
    private final NotificationDispatcher notifications;

    public WalletTopupOutcomeListener(PawapayOperationService operations, WalletService walletService,
                                      AuditService auditService, NotificationDispatcher notifications) {
        this.operations = operations;
        this.walletService = walletService;
        this.auditService = auditService;
        this.notifications = notifications;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCompleted(PawapayOperationCompletedEvent event) {
        if (event.kind() != PawapayOperationKind.DEPOSIT || event.purpose() != PawapayOperationPurpose.WALLET_TOPUP) {
            return;
        }
        PawapayOperationEntity op = operations.get(event.operationId());
        walletService.credit(op.getUserId(), op.getCurrency(), op.getAmount(), WalletTransactionType.TOP_UP,
                "pawapay:" + op.getId(), "pawapay-topup-" + op.getId());
        auditService.log("wallet_topup", op.getId(), "MOBILE_MONEY_CONFIRMED", op.getUserId(),
                Map.of("currency", op.getCurrency(), "amount", op.getAmount().toPlainString(), "provider",
                        op.getProvider()));
        notifications.notifyUser(op.getUserId(), "Recharge confirmée",
                "Recharge de " + WalletAmountText.format(op.getAmount(), op.getCurrency()) + " confirmée par "
                        + PawapayProviders.label(op.getProvider()) + ".",
                Map.of("type", "wallet_topup_confirmed", "topupId", op.getId().toString()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onFailed(PawapayOperationFailedEvent event) {
        if (event.kind() != PawapayOperationKind.DEPOSIT || event.purpose() != PawapayOperationPurpose.WALLET_TOPUP) {
            return;
        }
        log.info("Recharge mobile money {} échouée pour user {} : {}", event.operationId(), event.userId(),
                event.failureCode());
        auditService.log("wallet_topup", event.operationId(), "MOBILE_MONEY_FAILED", event.userId(),
                Map.of("failureCode", String.valueOf(event.failureCode())));
    }
}
