package com.yadony.api.payments.mobilemoney;

/**
 * Versement mobile money non soumis, ou refusé par pawaPay, pour une raison connue : rien n'est parti chez
 * l'opérateur. {@link #reason()} est un motif court et stable (audit, alerte admin), par exemple
 * {@code NO_MOBILE_MONEY_ACCOUNT}.
 */
public class MobileMoneyPayoutRefusedException extends RuntimeException {

    private final String reason;

    public MobileMoneyPayoutRefusedException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
