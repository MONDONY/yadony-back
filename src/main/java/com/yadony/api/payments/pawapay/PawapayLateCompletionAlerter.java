package com.yadony.api.payments.pawapay;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.payments.pawapay.events.PawapayLateCompletionEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Alerte critique, une par opération, quand pawaPay confirme une opération déjà classée en échec
 * ({@link PawapayLateCompletionEvent}). Avant, seule une ligne de log signalait ce désaccord, alors
 * qu'il peut cacher un double versement ou un dépôt jamais crédité.
 *
 * <p>{@code @EventListener} synchrone et non après commit : rien n'est écrit dans la transaction
 * d'{@code apply} (la transition a été refusée), et l'escalade enregistre l'alerte dans sa propre
 * transaction.
 */
@Component
public class PawapayLateCompletionAlerter {

    /** Préfixe du type d'alerte, suffixé par l'id de l'opération : 23 + 36 caractères, sous les 60. */
    static final String TYPE_PREFIX = "pawapay-late-completed-";

    private final AdminAlertEscalator escalator;

    public PawapayLateCompletionAlerter(AdminAlertEscalator escalator) {
        this.escalator = escalator;
    }

    @EventListener
    public void onLateCompletion(PawapayLateCompletionEvent event) {
        String detail = event.kind() == PawapayOperationKind.DEPOSIT
                ? "pawaPay a encaissé un dépôt déjà classé en échec : l'utilisateur a payé sans contrepartie"
                : "pawaPay a versé une opération déjà classée en échec : un repli a pu verser une seconde fois";
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("operationId", String.valueOf(event.operationId()));
        context.put("kind", String.valueOf(event.kind()));
        context.put("purpose", String.valueOf(event.purpose()));
        context.put("previousStatus", String.valueOf(event.previousStatus()));
        context.put("paymentId", String.valueOf(event.paymentId()));
        context.put("userId", String.valueOf(event.userId()));
        context.put("amount", String.valueOf(event.amount()));
        context.put("currency", String.valueOf(event.currency()));
        escalator.raiseOnce(TYPE_PREFIX + event.operationId(), "CRITICAL", detail, context);
    }
}
