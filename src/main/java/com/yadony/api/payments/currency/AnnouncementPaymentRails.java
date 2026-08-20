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
 * carte disponible   = travelerHasConnect ET CurrencyPaymentRails.accepte(devise, STRIPE)
 * especes disponible = toujours
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
     * @param currency          code devise libre (repli EUR via {@link SupportedCurrency#fromCodeOrDefault}
     *                          si absent ou inconnu)
     * @param travelerHasConnect true si le voyageur a un compte Stripe Connect actif
     *                           (onboarding terminé)
     * @return les moyens de paiement effectivement disponibles pour cette annonce ;
     *         jamais vide (l'espèce est toujours présente)
     */
    public static Set<PaymentMethod> availableFor(String currency, boolean travelerHasConnect) {
        SupportedCurrency supportedCurrency = SupportedCurrency.fromCodeOrDefault(currency);
        boolean cardAvailable = travelerHasConnect
                && CurrencyPaymentRails.allows(supportedCurrency, PaymentMethod.STRIPE);

        EnumSet<PaymentMethod> available = EnumSet.of(PaymentMethod.CASH);
        if (cardAvailable) {
            available.add(PaymentMethod.STRIPE);
        }
        return Set.copyOf(available);
    }
}
