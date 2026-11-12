package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Qui peut proposer un trajet par bateau : les entreprises (compte PRO) et les transporteurs
 * vérifiés (badge GP Pro). Le KYC reste exigé par ailleurs, à la publication. L'interrupteur du
 * mode ({@link TransportModeGuard}) se contrôle avant, cette règle ensuite.
 *
 * <p>Un trajet qui garde son mode bateau n'est pas recontrôlé : retirer le badge ne casse pas un
 * trajet déjà enregistré (même principe que l'interrupteur).
 */
@Component
public class BoatCarrierPolicy {

    static final String ERROR_CODE = "boat-carrier-not-verified";

    public boolean isVerifiedCarrier(UserEntity user) {
        return user != null && (user.isGpPro() || user.isProAccount());
    }

    public void assertCanPublishBoat(UserEntity user) {
        if (!isVerifiedCarrier(user)) {
            throw YadonyBusinessException.localized(HttpStatus.FORBIDDEN, ERROR_CODE,
                    "problem.boat-carrier-not-verified");
        }
    }
}
