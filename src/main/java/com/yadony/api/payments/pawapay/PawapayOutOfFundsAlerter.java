package com.yadony.api.payments.pawapay;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.payments.pawapay.events.PawapayOperationFailedEvent;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Alerte admin quand pawaPay refuse un versement ou un remboursement faute de fonds sur le
 * wallet Yadony ({@code PAWAPAY_WALLET_OUT_OF_FUNDS}). Le 18/10 en prod, un remboursement de
 * solde de 500 XOF (Orange Côte d'Ivoire) est passé en traitement manuel sans que personne soit
 * prévenu : {@link PawapayBalanceMonitor} ne tourne qu'avec un seuil configuré, nul par défaut.
 *
 * <p>Une alerte par wallet (pays + devise : pawaPay tient un wallet par pays), dédupliquée par
 * {@link AdminAlertEscalator#raiseOnce} tant qu'elle n'est pas résolue dans l'admin : une rafale
 * de versements refusés ne noie pas le canal. Deux chemins y mènent : l'échec final (callback ou
 * poller, {@link PawapayOperationFailedEvent}) et le refus dès la soumission
 * ({@link PawapaySubmissionService}), qui ne publie pas d'événement.
 */
@Component
public class PawapayOutOfFundsAlerter {

    static final String FAILURE_CODE = "PAWAPAY_WALLET_OUT_OF_FUNDS";
    static final String ALERT_TYPE_PREFIX = "PAWAPAY_OUT_OF_FUNDS_";
    private static final Logger log = LoggerFactory.getLogger(PawapayOutOfFundsAlerter.class);

    private final PawapayOperationRepository operations;
    private final AdminAlertEscalator alerts;

    public PawapayOutOfFundsAlerter(PawapayOperationRepository operations, AdminAlertEscalator alerts) {
        this.operations = operations;
        this.alerts = alerts;
    }

    // fallbackExecution : un événement publié hors transaction (test, appel direct) n'est
    // pas ignoré. AFTER_COMMIT : l'alerte part une fois l'échec réellement enregistré.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onFailed(PawapayOperationFailedEvent event) {
        if (!FAILURE_CODE.equals(event.failureCode())) {
            return;
        }
        operations.findById(event.operationId()).ifPresent(op -> alert(op, event.failureCode()));
    }

    /**
     * Même alerte (même type, donc même déduplication par wallet), levée AVANT toute soumission :
     * le remboursement automatique a lu le solde pawaPay et n'a rien envoyé, le montant dépassant
     * ce que le wallet peut verser en sécurité ({@code maxSafe}). Aucune opération n'existe.
     */
    public void alertBalanceTooLow(String country, String currency, String provider, PawapayOperationKind kind,
                                   BigDecimal amount, BigDecimal balance, BigDecimal maxSafe) {
        try {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("country", String.valueOf(country));
            context.put("currency", String.valueOf(currency));
            context.put("provider", String.valueOf(provider));
            context.put("kind", String.valueOf(kind));
            context.put("amount", amount == null ? "" : amount.toPlainString());
            context.put("walletBalance", balance == null ? "" : balance.toPlainString());
            context.put("maxSafe", maxSafe == null ? "" : maxSafe.toPlainString());
            context.put("submitted", "false");
            alerts.raiseOnce(ALERT_TYPE_PREFIX + country + "_" + currency,
                    "Wallet pawaPay " + country + " " + currency + " trop bas : " + kind + " de "
                            + context.get("amount") + " " + currency + " non envoyé (solde "
                            + context.get("walletBalance") + ", au plus " + context.get("maxSafe")
                            + " en sécurité). Alimenter le wallet sur le tableau de bord pawaPay, puis relancer "
                            + "le remboursement passé en manuel.",
                    context);
        } catch (RuntimeException e) {
            log.error("pawaPay {} {} : alerte solde insuffisant non levée", country, currency, e);
        }
    }

    /** Lève l'alerte si {@code failureCode} est le manque de fonds ; sans effet sinon. */
    void alert(PawapayOperationEntity op, String failureCode) {
        if (!FAILURE_CODE.equals(failureCode)) {
            return;
        }
        try {
            String wallet = op.getCountry() + " " + op.getCurrency();
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("country", String.valueOf(op.getCountry()));
            context.put("currency", String.valueOf(op.getCurrency()));
            context.put("provider", String.valueOf(op.getProvider()));
            context.put("kind", String.valueOf(op.getKind()));
            context.put("amount", op.getAmount() == null ? "" : op.getAmount().toPlainString());
            context.put("operationId", String.valueOf(op.getId()));
            alerts.raiseOnce(ALERT_TYPE_PREFIX + op.getCountry() + "_" + op.getCurrency(),
                    "Wallet pawaPay " + wallet + " sans fonds : " + op.getKind() + " de "
                            + context.get("amount") + " " + op.getCurrency()
                            + " refusé. Alimenter le wallet sur le tableau de bord pawaPay, puis traiter "
                            + "les remboursements passés en manuel.",
                    context);
        } catch (RuntimeException e) {
            // L'alerte ne doit jamais casser le traitement de l'échec (remboursement passé
            // en manuel, statut du versement) : on journalise et on continue.
            log.error("pawaPay {} : alerte manque de fonds non levée", op.getId(), e);
        }
    }
}
