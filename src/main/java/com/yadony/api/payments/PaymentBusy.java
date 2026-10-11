package com.yadony.api.payments;

import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;

/**
 * Paiement verrouillé par une validation concurrente (webhook Stripe, autre confirmation) même
 * après les rejeux de {@link com.yadony.api.common.TransientLockRetry} : rien n'est perdu, le même
 * geste réessayé un instant plus tard aboutit. 409 RFC 7807 {@code payment-busy} plutôt qu'une 500
 * générique (YADONY-BACK-STAGING-18).
 */
public final class PaymentBusy {

    public static final String CODE = "payment-busy";

    private PaymentBusy() {
    }

    public static YadonyBusinessException exception() {
        return new YadonyBusinessException(HttpStatus.CONFLICT, CODE, "Payment Busy",
                "Paiement en cours de validation, réessayez dans un instant");
    }
}
