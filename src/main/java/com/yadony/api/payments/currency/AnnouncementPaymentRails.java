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

    /**
     * Retire d'un choix de moyens de paiement ceux que la devise n'autorise pas
     * ({@link CurrencyPaymentRails}) : la carte en zone CFA, le mobile money ailleurs.
     * Jamais vide : sans rail restant, l'espèce, toujours possible.
     *
     * <p>Recette du 2026-09-09 : une annonce XOF enregistrait la carte parmi ses moyens de
     * paiement, l'app la proposait, et le checkout carte ouvrait un séquestre Stripe de
     * 6 600 « euros » pour 6 600 XOF. Le filtre s'applique à l'écriture de l'annonce.
     */
    public static Set<PaymentMethod> restrictToCurrency(Set<PaymentMethod> methods, String currency) {
        SupportedCurrency supportedCurrency = SupportedCurrency.fromCodeOrDefault(currency);
        EnumSet<PaymentMethod> kept = EnumSet.noneOf(PaymentMethod.class);
        if (methods != null) {
            for (PaymentMethod method : methods) {
                if (CurrencyPaymentRails.allows(supportedCurrency, method)) {
                    kept.add(method);
                }
            }
        }
        if (kept.isEmpty()) {
            kept.add(PaymentMethod.CASH);
        }
        return kept;
    }

    /**
     * Moyens réellement fournissables par un voyageur sur une transaction dans {@code currency},
     * parmi ceux que l'expéditeur accepte : l'intersection de {@link #availableFor} (devise et
     * capacités du voyageur) et du choix explicite. Jamais de moyen que la devise interdit,
     * jamais de carte sans compte Connect, jamais de mobile money sans compte de versement.
     */
    public static Set<PaymentMethod> offerable(Set<PaymentMethod> accepted, String currency,
                                               boolean travelerHasConnect, boolean travelerHasMobileMoney) {
        Set<PaymentMethod> available = availableFor(currency, travelerHasConnect, travelerHasMobileMoney);
        EnumSet<PaymentMethod> kept = EnumSet.noneOf(PaymentMethod.class);
        if (accepted != null) {
            for (PaymentMethod method : accepted) {
                if (available.contains(method)) {
                    kept.add(method);
                }
            }
        }
        return kept;
    }
}
