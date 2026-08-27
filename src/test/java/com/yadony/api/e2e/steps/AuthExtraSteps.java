package com.yadony.api.e2e.steps;

import io.cucumber.java.fr.Quand;

import java.util.HashMap;
import java.util.Map;

/**
 * Steps for the secondary auth/account endpoints: FCM token, privacy settings,
 * analytics consent, PRO upgrade/downgrade and user blocking.
 */
public class AuthExtraSteps extends AbstractSteps {

    @Quand("je complète mon profil avec l'email {string}, la ville {string} et la date de naissance {string}")
    public void whenCompleteProfile(String email, String city, String birthDate) {
        Map<String, Object> body = new HashMap<>();
        body.put("email", email);
        body.put("city", city);
        body.put("birthDate", birthDate);
        store(asCurrentUser().body(body).patch("/auth/me"));
    }

    @Quand("je mets à jour mon token FCM")
    public void whenUpdateFcmToken() {
        Map<String, Object> body = new HashMap<>();
        body.put("fcmToken", "fcm-token-abc123");
        body.put("deviceId", "device-001");
        body.put("deviceName", "Pixel 8");
        body.put("platform", "android");
        store(asCurrentUser().body(body).put("/auth/me/fcm-token"));
    }

    @Quand("je consulte mes paramètres de confidentialité")
    public void whenGetPrivacy() {
        store(asCurrentUser().get("/auth/me/privacy-settings"));
    }

    @Quand("je mets à jour mes paramètres de confidentialité")
    public void whenUpdatePrivacy() {
        store(asCurrentUser().body(Map.of("contactKycOnly", true)).put("/auth/me/privacy-settings"));
    }

    @Quand("je consulte mon consentement analytique")
    public void whenGetConsent() {
        store(asCurrentUser().get("/auth/me/analytics-consent"));
    }

    @Quand("je donne mon consentement analytique")
    public void whenGiveConsent() {
        Map<String, Object> body = new HashMap<>();
        body.put("granted", true);
        body.put("policyVersion", "v1");
        body.put("source", "app");
        store(asCurrentUser().body(body).put("/auth/me/analytics-consent"));
    }

    @Quand("je mets à jour mon profil pro")
    public void whenUpgradePro() {
        Map<String, Object> body = new HashMap<>();
        body.put("companyName", "Yadony Transport SARL");
        body.put("siret", "12345678901234");
        store(asCurrentUser().body(body).post("/auth/me/upgrade-to-pro"));
    }

    @Quand("je repasse mon compte en standard")
    public void whenDowngradePro() {
        store(asCurrentUser().delete("/auth/me/upgrade-to-pro"));
    }

    @Quand("je bloque l'utilisateur {string}")
    public void whenBlockUser(String alias) {
        store(asCurrentUser().body(Map.of("blockedUserId", ctx.getId(alias).toString()))
                .post("/users/me/blocks"));
    }

    @Quand("je consulte ma liste de blocage")
    public void whenListBlocks() {
        store(asCurrentUser().get("/users/me/blocks"));
    }

    @Quand("je débloque l'utilisateur {string}")
    public void whenUnblockUser(String alias) {
        store(asCurrentUser().delete("/users/me/blocks/{id}", ctx.getId(alias)));
    }
}
