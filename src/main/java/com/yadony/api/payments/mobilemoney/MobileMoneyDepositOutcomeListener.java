package com.yadony.api.payments.mobilemoney;

import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.events.PawapayOperationCompletedEvent;
import com.yadony.api.payments.pawapay.events.PawapayOperationFailedEvent;
import java.util.Map;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tout premiers écouteurs des événements pawaPay génériques {@link PawapayOperationCompletedEvent}
 * / {@link PawapayOperationFailedEvent} : traduit une transition d'opération en effet
 * métier pour le rail mobile money (passage en séquestre, notification d'échec).
 *
 * <p><b>Règle 18 du projet, non négociable</b> — {@code @TransactionalEventListener(phase =
 * AFTER_COMMIT)} combiné à {@code @Transactional(propagation = REQUIRES_NEW)} sur les deux
 * méthodes, jamais un {@code @EventListener} seul. Les deux événements sont publiés À
 * L'INTÉRIEUR de la transaction qui applique la transition
 * ({@code PawapayOperationService#apply}, voir le Javadoc des deux événements). Un
 * écouteur simple s'exécuterait avant le commit de cette transaction — donc sur une
 * transition pas encore visible des autres connexions, et même si cette transaction
 * était ensuite annulée — et confirmerait un séquestre pour un deposit qui n'a jamais été
 * durablement enregistré.
 */
@Component
public class MobileMoneyDepositOutcomeListener {

    private final MobileMoneyBidPaymentService service;
    private final AdminAlertService adminAlert;

    public MobileMoneyDepositOutcomeListener(MobileMoneyBidPaymentService service, AdminAlertService adminAlert) {
        this.service = service;
        this.adminAlert = adminAlert;
    }

    /**
     * {@code confirmEscrow} peut atteindre un point
     * irréversible (deposit après annulation : refund déjà soumis, appel HTTP déjà parti)
     * puis échouer juste après pour une raison purement réseau — la transaction ambiante
     * est alors annulée, mais rien ne rejouera jamais cette confirmation :
     * {@code PawapayOperationCompletedEvent} n'est publié qu'une seule fois par opération,
     * et le poller de réconciliation ne balaie que les opérations encore OUVERTES (cette
     * opération est déjà COMPLETED). Sans ce garde-fou, l'expéditeur resterait débité,
     * l'argent chez pawaPay, le paiement revenu PENDING, le bid annulé, aucun remboursement
     * soumis — et personne n'en saurait jamais rien. On intercepte, on alerte un
     * administrateur, PUIS on repropage à l'identique : la transaction doit toujours être
     * annulée (jamais avaler l'erreur, ce qui commiterait silencieusement un état partiel),
     * mais un humain est désormais prévenu dans la minute.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCompleted(PawapayOperationCompletedEvent event) {
        if (event.kind() != PawapayOperationKind.DEPOSIT || event.paymentId() == null) return;
        try {
            service.confirmEscrow(event.operationId(), event.paymentId());
        } catch (RuntimeException e) {
            adminAlert.raise("PAWAPAY_ESCROW_CONFIRMATION_FAILED",
                    "confirmEscrow a échoué pour le deposit " + event.operationId() + " / paiement "
                            + event.paymentId() + " : " + e.getMessage()
                            + ". Vérifier l'état du paiement et, si un remboursement était en cours, son issue chez pawaPay.",
                    Map.of("operationId", event.operationId().toString(), "paymentId", event.paymentId().toString()));
            throw e;
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onFailed(PawapayOperationFailedEvent event) {
        if (event.kind() != PawapayOperationKind.DEPOSIT || event.paymentId() == null) return;
        service.notifyDepositFailed(event.operationId(), event.paymentId(), event.failureCode());
    }
}
