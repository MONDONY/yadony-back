package com.yadony.api.matching;

import com.yadony.api.payments.cash.PaymentMethod;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Lecture du paramètre de recherche {@code paymentMethods} (FLUTTER-G0).
 *
 * <p>Accepte {@code STRIPE} ou son alias {@code CARD}, {@code CASH} et {@code MOBILE_MONEY},
 * en liste répétée ou séparée par des virgules, sans tenir compte de la casse. Une valeur
 * inconnue ou retirée (WAVE, ORANGE_MONEY) est ignorée plutôt que de faire échouer la
 * recherche, comme un {@code transportMode} inconnu.
 */
public final class PaymentMethodFilter {

    private PaymentMethodFilter() {}

    public static Set<PaymentMethod> parse(Collection<String> raw) {
        EnumSet<PaymentMethod> methods = EnumSet.noneOf(PaymentMethod.class);
        if (raw == null) {
            return methods;
        }
        for (String entry : raw) {
            if (entry == null) {
                continue;
            }
            for (String token : entry.split(",")) {
                PaymentMethod method = toMethod(token.trim().toUpperCase(Locale.ROOT));
                if (method != null) {
                    methods.add(method);
                }
            }
        }
        return methods;
    }

    private static PaymentMethod toMethod(String token) {
        return switch (token) {
            case "CARD", "STRIPE" -> PaymentMethod.STRIPE;
            case "CASH" -> PaymentMethod.CASH;
            case "MOBILE_MONEY" -> PaymentMethod.MOBILE_MONEY;
            default -> null;
        };
    }
}
