package com.yadony.api.payments.supplement;

import com.yadony.api.auth.UserEntity;
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

    /**
     * Le voyageur peut-il recevoir un supplément sur ce rail ? Carte : compte Connect complet
     * (versement par Transfer, sinon encaissé sans destination) ; mobile money : compte mobile money dans la
     * devise du colis ; espèces : toujours. Règle unique de la création et du bouton de l'app.
     */
    public boolean payableBy(UserEntity traveler, String currency) {
        return switch (this) {
            case STRIPE -> stripeAccountReady(traveler);
            case PAWAPAY -> traveler.canReceiveMobileMoney(currency);
            case CASH -> true;
        };
    }

    /**
     * Compte Connect prêt à recevoir un Transfer : même exigence que le paiement principal
     * ({@code PaymentService.createEscrow} : {@code ONBOARDING_COMPLETE} et identifiant présent).
     */
    public static boolean stripeAccountReady(UserEntity traveler) {
        return traveler != null
                && traveler.getStripeAccountStatus() == com.yadony.api.auth.StripeAccountStatus.ONBOARDING_COMPLETE
                && traveler.getStripeAccountId() != null && !traveler.getStripeAccountId().isBlank();
    }

    static YadonyBusinessException unsupported() {
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "supplement-rail-unsupported",
                "Supplement Rail Unsupported", "Un supplément n'est pas encore possible avec ce moyen de paiement");
    }
}
