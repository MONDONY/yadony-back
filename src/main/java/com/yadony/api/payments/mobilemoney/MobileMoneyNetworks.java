package com.yadony.api.payments.mobilemoney;

import com.yadony.api.auth.UserEntity;
import java.util.List;

/**
 * Réseaux mobile money lus sur le résumé de {@link UserEntity}, pour l'ancien contrat
 * {@code /payments/mobile-money/account} seulement. L'acceptation d'un paiement se lit sur les comptes
 * de versement ({@link MobileMoneyPayoutAccounts#accepts}), jamais ici.
 *
 * <p>Repli hérité : un compte activé avant V255 n'a que {@code mobile_money_provider} ; il vaut
 * alors une liste d'un seul réseau.
 */
public final class MobileMoneyNetworks {

    private MobileMoneyNetworks() {}

    /** Codes acceptés, dans l'ordre enregistré (repli sur l'opérateur unique, s'il est renseigné). */
    public static List<String> acceptedCodes(UserEntity traveler) {
        List<String> codes = traveler.getMobileMoneyProviderList();
        String single = traveler.getMobileMoneyProvider();
        if (codes.isEmpty() && single != null && !single.isBlank()) {
            return List.of(single);
        }
        return codes;
    }
}
