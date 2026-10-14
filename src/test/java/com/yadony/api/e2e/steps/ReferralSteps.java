package com.yadony.api.e2e.steps;

import io.cucumber.java.fr.Quand;

import java.util.Map;

/**
 * Steps for the referral programme (referral code lookup, regeneration, redemption).
 */
public class ReferralSteps extends AbstractSteps {

    @Quand("je consulte mon code de parrainage")
    public void whenGetReferral() {
        store(asCurrentUser().get("/me/referral"));
        String code = lastResponse().jsonPath().getString("code");
        if (code != null) {
            ctx.saveString("referral-code", code);
        }
    }

    @Quand("je régénère mon code de parrainage")
    public void whenRegenerateReferral() {
        store(asCurrentUser().post("/me/referral/regenerate"));
        String code = lastResponse().jsonPath().getString("code");
        if (code != null) {
            ctx.saveString("referral-code", code);
        }
    }

    @Quand("je consulte mes filleuls")
    public void whenGetReferees() {
        store(asCurrentUser().get("/me/referral/referees"));
    }

    @Quand("j'utilise le code de parrainage sauvegardé")
    public void whenRedeemReferral() {
        store(asCurrentUser().body(Map.of("code", ctx.getString("referral-code")))
                .post("/referral/redeem"));
    }
}
