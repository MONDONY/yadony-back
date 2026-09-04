package com.yadony.api.payments.currency;

import com.yadony.api.payments.cash.PaymentMethod;
import java.util.EnumSet;
import java.util.Set;

/**
 * Compose {@link CurrencyPaymentRails} avec l'état Stripe Connect du voyageur pour donner
 * la liste effective des moyens de paiement d'une annonce.
 *
 * <p>Règle, verbatim :
 * <pre>
 * carte disponible        = travelerHasConnect ET CurrencyPaymentRails.accepte(devise, STRIPE)
 * mobile money disponible = travelerHasMobileMoney ET CurrencyPaymentRails.accepte(devise, MOBILE_MONEY)
 * especes disponible      = toujours
 * </pre>
 *
 * <p>L'espèce n'est jamais retirée : c'est l'invariant qui garde une annonce vendable même
 * quand le voyageur n'a pas terminé son onboarding Stripe Connect (ou que la devise ne
 * permet structurellement pas le rail carte, cas XOF/XAF, cf. {@link CurrencyPaymentRails}).
 */
public final class AnnouncementPaymentRails {

    private AnnouncementPaymentRails() {
    }

    /**
     * @param currency               code devise libre (repli EUR)
     * @param travelerHasConnect     compte Stripe Connect actif
     * @param travelerHasMobileMoney compte de versement mobile money actif
     *                               ({@code UserEntity#hasActiveMobileMoney()}, un champ déjà chargé :
     *                               aucune requête supplémentaire sur le fil de recherche)
     * @return jamais vide (l'espèce est toujours présente)
     */
    public static Set<PaymentMethod> availableFor(String currency, boolean travelerHasConnect,
                                                  boolean travelerHasMobileMoney) {
        SupportedCurrency supportedCurrency = SupportedCurrency.fromCodeOrDefault(currency);
        boolean cardAvailable = travelerHasConnect
                && CurrencyPaymentRails.allows(supportedCurrency, PaymentMethod.STRIPE);
        boolean mobileMoneyAvailable = travelerHasMobileMoney
                && CurrencyPaymentRails.allows(supportedCurrency, PaymentMethod.MOBILE_MONEY);

        EnumSet<PaymentMethod> available = EnumSet.of(PaymentMethod.CASH);
        if (cardAvailable) {
            available.add(PaymentMethod.STRIPE);
        }
        if (mobileMoneyAvailable) {
            available.add(PaymentMethod.MOBILE_MONEY);
        }
        return Set.copyOf(available);
    }
}
