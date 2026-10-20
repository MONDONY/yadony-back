package com.yadony.api.payments.mobilemoney;

import com.yadony.api.common.i18n.MessagesResolver;
import com.yadony.api.payments.mobilemoney.dto.MobileMoneyPaymentStatusResponse.OperationView;
import com.yadony.api.payments.pawapay.PawapayFailureCodes;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapayProviders;
import com.yadony.api.payments.pawapay.PawapayValidationHints;
import org.springframework.stereotype.Component;

/**
 * Vue d'un dépôt mobile money de colis (réservation, fil de négociation, supplément) lue par
 * l'app. Les trois rails la partagent : même motif d'échec traduit (clés {@code topup.failure.*}
 * de la recharge du solde) et même consigne de validation par opérateur.
 *
 * <p>Le message brut de l'opérateur n'est jamais reflété : {@code failureMessage} reprend le
 * texte traduit, {@code failureCode} reste le code pawaPay brut pour les anciens clients.
 */
@Component
public class MobileMoneyOperationViews {

    private final MessagesResolver messagesResolver;
    private final PawapayValidationHints validationHints;

    public MobileMoneyOperationViews(MessagesResolver messagesResolver, PawapayValidationHints validationHints) {
        this.messagesResolver = messagesResolver;
        this.validationHints = validationHints;
    }

    /** Vue du dépôt ; les trois champs d'échec sont {@code null} tant que le dépôt n'est pas en échec. */
    public OperationView view(PawapayOperationEntity op) {
        String reasonCode = null;
        String reason = null;
        if (isFailed(op)) {
            reasonCode = PawapayFailureCodes.toYadony(op.getFailureCode());
            reason = translate(reasonCode);
        }
        return new OperationView(op.getId(), op.getStatus().name(), op.getProvider(),
                PawapayProviders.label(op.getProvider()), op.getMsisdnMasked(), op.getAuthorizationUrl(),
                op.getFailureCode(), reason, reasonCode, reason, validationHints.forProvider(op.getProvider()));
    }

    /** Motif traduit du refus d'un dépôt, pour le détail du 422 {@code mobile-money-deposit-rejected}. */
    public String rejectedDetail(PawapayOperationEntity op) {
        return translate(PawapayFailureCodes.toYadony(op.getFailureCode()));
    }

    private static boolean isFailed(PawapayOperationEntity op) {
        return op.getStatus() == PawapayOperationStatus.FAILED || op.getStatus() == PawapayOperationStatus.SUBMIT_REJECTED;
    }

    private String translate(String reasonCode) {
        return messagesResolver.forRequest().get("topup.failure." + reasonCode);
    }
}
