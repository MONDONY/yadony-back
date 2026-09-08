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
        return new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "mobile-money-disabled",
                "Mobile Money Disabled", "Le mobile money n'est pas encore disponible.");
    }

    /**
     * 502 : pawaPay ne répond pas (panne réseau, 5xx) ou répond une donnée inexploitable —
     * dans les deux cas ce n'est pas la faute de l'utilisateur, donc jamais un 422 métier.
     */
    public static YadonyBusinessException providerUnavailable() {
        return new YadonyBusinessException(HttpStatus.BAD_GATEWAY, "mobile-money-provider-unavailable",
                "Mobile Money Provider Unavailable",
                "Le service mobile money ne répond pas. Réessayez dans quelques instants.");
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
