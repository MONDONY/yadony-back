package com.yadony.api.e2e.steps;

import io.cucumber.java.fr.Etantdonné;
import io.cucumber.java.fr.Quand;
import io.restassured.response.Response;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Steps for admin endpoints (promo-code management). ADMIN authority is granted
 * through the X-Test-Roles header; the underlying user row is created via register.
 */
public class AdminSteps extends AbstractSteps {

    @Etantdonné("l'utilisateur {string} est authentifié en tant qu'ADMIN")
    public void givenAuthenticatedAdmin(String uid) {
        ctx.setCurrentUser(uid, "ROLE_ADMIN,PROMO_MANAGE,DISPUTE_RESOLVE");
    }

    @Quand("je crée un code promo {string} sauvegardé sous {string}")
    public void whenCreatePromo(String code, String alias) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("rate", 0.5);
        body.put("target", "ANY");
        body.put("perUserLimit", 1);
        Response resp = asCurrentUser().body(body).post("/admin/promo-codes");
        store(resp);
        String id = resp.jsonPath().getString("id");
        if (id != null) {
            ctx.saveId(alias, UUID.fromString(id));
        }
    }

    @Quand("je consulte les codes promo")
    public void whenListPromos() {
        store(asCurrentUser().get("/admin/promo-codes"));
    }

    @Quand("je consulte le code promo {string}")
    public void whenGetPromo(String alias) {
        store(asCurrentUser().get("/admin/promo-codes/{id}", ctx.getId(alias)));
    }

    @Quand("je mets le code promo {string} en pause")
    public void whenPausePromo(String alias) {
        store(asCurrentUser().body(Map.of("status", "DISABLED"))
                .put("/admin/promo-codes/{id}/status", ctx.getId(alias)));
    }

    @Quand("je supprime le code promo {string}")
    public void whenDeletePromo(String alias) {
        store(asCurrentUser().delete("/admin/promo-codes/{id}", ctx.getId(alias)));
    }
}
