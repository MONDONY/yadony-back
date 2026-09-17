package com.yadony.api.payments.wallet;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
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
 *
 * <p><b>Garde-fou {@code onCompleted}</b> — {@code PawapayOperationCompletedEvent} n'est publié
 * qu'une seule fois par opération, et le poller de réconciliation ne rebalaie que les opérations
 * encore OUVERTES : cette opération est déjà {@code COMPLETED}, rien ne rejouera jamais ce
 * crédit. Si {@code credit}, l'audit ou la notification lèvent après un commit pawaPay réussi,
 * l'argent est encaissé côté pawaPay mais jamais crédité au portefeuille, sans que personne ne le
 * sache — le corps est donc entouré d'un {@code try/catch RuntimeException} qui alerte un
 * administrateur ({@link AdminAlertService}) PUIS repropage à l'identique (même motif que
 * {@code MobileMoneyDepositOutcomeListener#onCompleted}) : la transaction doit toujours être
 * annulée (jamais avaler l'erreur, ce qui commiterait un état partiel), mais un humain est
 * désormais prévenu dans la minute pour créditer manuellement.
 *
 * <p><b>Garde-fou {@code onFailed}</b> — aucun argent n'a bougé (le dépôt a échoué chez pawaPay
 * avant tout crédit) : une erreur ici ne peut perdre qu'une ligne d'audit, jamais de l'argent. Le
 * corps est donc entouré d'un {@code try/catch RuntimeException} qui journalise en {@code ERROR}
 * puis AVALE l'exception (pas de {@code adminAlertService.raise}, pas de repropagation) — à la
 * différence de {@code onCompleted}, il n'y a rien à réconcilier manuellement, et repropager
 * risquerait d'empêcher d'autres écouteurs {@code AFTER_COMMIT} du même événement de s'exécuter.
 */
@Component
public class WalletTopupOutcomeListener {

    private static final Logger log = LoggerFactory.getLogger(WalletTopupOutcomeListener.class);

    private final PawapayOperationService operations;
    private final WalletService walletService;
    private final AuditService auditService;
    private final NotificationDispatcher notifications;
    private final AdminAlertService adminAlertService;

    public WalletTopupOutcomeListener(PawapayOperationService operations, WalletService walletService,
                                      AuditService auditService, NotificationDispatcher notifications,
                                      AdminAlertService adminAlertService) {
        this.operations = operations;
        this.walletService = walletService;
        this.auditService = auditService;
        this.notifications = notifications;
        this.adminAlertService = adminAlertService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCompleted(PawapayOperationCompletedEvent event) {
        if (event.kind() != PawapayOperationKind.DEPOSIT || event.purpose() != PawapayOperationPurpose.WALLET_TOPUP) {
            return;
        }
        PawapayOperationEntity op = operations.get(event.operationId());
        try {
            walletService.credit(op.getUserId(), op.getCurrency(), op.getAmount(), WalletTransactionType.TOP_UP,
                    "pawapay:" + op.getId(), "pawapay-topup-" + op.getId());
            auditService.log("wallet_topup", op.getId(), "MOBILE_MONEY_CONFIRMED", op.getUserId(),
                    Map.of("currency", op.getCurrency(), "amount", op.getAmount().toPlainString(), "provider",
                            op.getProvider()));
            notifications.notifyUser(op.getUserId(), "Recharge confirmée",
                    "Recharge de " + WalletAmountText.format(op.getAmount(), op.getCurrency()) + " confirmée par "
                            + PawapayProviders.label(op.getProvider()) + ".",
                    Map.of("type", "wallet_topup_confirmed", "topupId", op.getId().toString()));
        } catch (RuntimeException e) {
            adminAlertService.raise("WALLET_TOPUP_CREDIT_FAILED",
                    "Recharge mobile money " + op.getId() + " confirmée par pawaPay mais crédit du wallet en échec "
                            + "pour l'utilisateur " + op.getUserId() + " : " + e.getMessage()
                            + ". L'argent est encaissé chez pawaPay ; créditer manuellement le portefeuille après "
                            + "vérification.",
                    Map.of("operationId", op.getId().toString(), "userId", op.getUserId().toString(),
                            "currency", op.getCurrency(), "amount", op.getAmount().toPlainString(),
                            "error", String.valueOf(e.getMessage())));
            throw e;
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onFailed(PawapayOperationFailedEvent event) {
        if (event.kind() != PawapayOperationKind.DEPOSIT || event.purpose() != PawapayOperationPurpose.WALLET_TOPUP) {
            return;
        }
        try {
            log.info("Recharge mobile money {} échouée pour user {} : {}", event.operationId(), event.userId(),
                    event.failureCode());
            auditService.log("wallet_topup", event.operationId(), "MOBILE_MONEY_FAILED", event.userId(),
                    Map.of("failureCode", String.valueOf(event.failureCode())));
        } catch (RuntimeException e) {
            // Aucun argent n'a bougé pour un dépôt en échec : seule une ligne d'audit peut être
            // perdue. Journalisée puis avalée, jamais repropagée (voir le Javadoc de classe).
            log.error("Audit de l'échec de recharge {} impossible pour user {} : {}", event.operationId(),
                    event.userId(), e.getMessage(), e);
        }
    }
}
