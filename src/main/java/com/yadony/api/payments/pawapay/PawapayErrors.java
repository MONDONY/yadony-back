package com.yadony.api.payments.pawapay;

import com.yadony.api.common.YadonyBusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

/**
 * Les deux erreurs métier partagées par tout le rail mobile money. Une seule définition par
 * code RFC 7807 : le client Flutter branche son comportement sur ces codes, un libellé ou un
 * statut ne se corrige qu'ici.
 */
public final class PawapayErrors {

    private static final Logger log = LoggerFactory.getLogger(PawapayErrors.class);

    private PawapayErrors() {}

    /** 422 : le rail est coupé ({@code yadony.pawapay.enabled=false}), aucun nouveau mouvement d'argent. */
    public static YadonyBusinessException disabled() {
        return YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-disabled", "problem.mobile-money-disabled");
    }

    /**
     * 422 : une recharge du portefeuille est déjà en attente de validation sur le téléphone
     * de l'utilisateur. Deux émetteurs, un seul libellé : la garde applicative de
     * {@code WalletMobileMoneyTopupService} (chemin normal) et l'index unique partiel
     * {@code uq_pawapay_ops_live_wallet_topup} (course entre deux requêtes ; V331 : il ne compte que
     * les recharges dont la garde {@code topup_guard} n'a pas été libérée après 15 min).
     */
    public static YadonyBusinessException walletTopupAlreadyPending() {
        return YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "topup-already-pending", "problem.topup-already-pending");
    }

    /**
     * 502 : pawaPay ne répond pas (panne réseau, 5xx) ou répond une donnée inexploitable —
     * dans les deux cas ce n'est pas la faute de l'utilisateur, donc jamais un 422 métier.
     */
    public static YadonyBusinessException providerUnavailable() {
        return YadonyBusinessException.localized(HttpStatus.BAD_GATEWAY, "mobile-money-provider-unavailable", "problem.mobile-money-provider-unavailable");
    }

    /**
     * Même 502, journalisé : uniquement l'étape pawaPay et le contexte métier ({@code context},
     * ex. « l'activation mobile money de <userId> »), jamais un numéro ni une charge pawaPay.
     */
    public static YadonyBusinessException providerUnavailable(String step, String context, Exception cause) {
        log.error("pawaPay indisponible ({}) lors de {} : {}", step, context, cause.toString());
        return providerUnavailable();
    }
}
