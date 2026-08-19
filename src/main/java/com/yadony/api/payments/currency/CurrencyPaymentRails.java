package com.yadony.api.payments.currency;

import com.yadony.api.payments.cash.PaymentMethod;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Source unique de la regle « quelle devise autorise quel rail de paiement ».
 *
 * <p>La zone CFA (XOF, XAF) n'est pas un pays Stripe Connect : le versement au voyageur
 * y est structurellement impossible, pas un choix produit. La contrainte est
 * <b>directionnelle</b> : l'encaissement (PaymentIntent) fonctionne depuis n'importe
 * quelle carte, seul le versement (Transfer) echoue. C'est pourquoi la carte est retiree
 * ici cote colis alors qu'elle reste utilisable pour financer le portefeuille cote
 * commission (cf. docs/specs/2026-08-19-multidevise-etat-des-lieux.md section 2.5).
 */
public final class CurrencyPaymentRails {

    private static final Map<SupportedCurrency, Set<PaymentMethod>> TABLE = buildTable();

    private CurrencyPaymentRails() {
    }

    private static Map<SupportedCurrency, Set<PaymentMethod>> buildTable() {
        Map<SupportedCurrency, Set<PaymentMethod>> table = new EnumMap<>(SupportedCurrency.class);
        table.put(SupportedCurrency.EUR,
                EnumSet.of(PaymentMethod.STRIPE, PaymentMethod.CASH));
        Set<PaymentMethod> cfaRails =
                EnumSet.of(PaymentMethod.CASH, PaymentMethod.WAVE, PaymentMethod.ORANGE_MONEY);
        table.put(SupportedCurrency.XOF, cfaRails);
        table.put(SupportedCurrency.XAF, cfaRails);
        return table.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
    }

    public static Set<PaymentMethod> allowedFor(SupportedCurrency currency) {
        return TABLE.get(currency);
    }

    public static boolean allows(SupportedCurrency currency, PaymentMethod method) {
        return allowedFor(currency).contains(method);
    }

    public static boolean allowsCode(String currencyCode, PaymentMethod method) {
        return allows(SupportedCurrency.fromCodeOrDefault(currencyCode), method);
    }
}
