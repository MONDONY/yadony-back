package com.yadony.api.payments.supplement;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.cash.PaymentMethod;
import org.springframework.http.HttpStatus;

/** Rail du supplément : TOUJOURS celui du colis (plan §1.3-g). */
public enum BidSupplementRail {
    STRIPE,
    PAWAPAY,
    CASH;

    /**
     * @throws YadonyBusinessException 422 {@code supplement-rail-unsupported} pour les rails hérités
     *         WAVE et ORANGE_MONEY, qui n'ont aucun moyen d'encaisser un complément.
     */
    public static BidSupplementRail from(PaymentMethod method) {
        if (method == null) {
            return STRIPE;
        }
        return switch (method) {
            case STRIPE -> STRIPE;
            case MOBILE_MONEY -> PAWAPAY;
            case CASH -> CASH;
            case WAVE, ORANGE_MONEY -> throw unsupported();
        };
    }

    static YadonyBusinessException unsupported() {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "supplement-rail-unsupported",
                "Supplement Rail Unsupported", "Un supplément n'est pas encore possible avec ce moyen de paiement");
    }
}
