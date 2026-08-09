package com.yadony.api.e2e.steps;

import io.cucumber.java.fr.Alors;
import io.cucumber.java.fr.Etantdonné;
import io.cucumber.java.fr.Quand;
import io.restassured.response.Response;
import org.assertj.core.api.Assertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class AuthSteps extends AbstractSteps {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ── Given ─────────────────────────────────────────────────────────────────

    @Etantdonné("un token Firebase pour l'uid {string}")
    public void givenFirebaseToken(String uid) {
        ctx.setCurrentUser(uid, "");
    }

    @Etantdonné("un utilisateur VOYAGEUR enregistré avec l'uid {string} et le téléphone {string}")
    public void givenRegisteredTraveler(String uid, String phone) {
        ctx.setCurrentUser(uid, "ROLE_TRAVELER");
        store(asCurrentUser().header("X-Test-Phone", phone).body(Map.of(
                "phoneNumber", phone,
                "roles", Set.of("TRAVELER")
        )).post("/auth/register"));
        // Depuis le voyageur universel (Stripe = capacité optionnelle), un trajet
        // publié sans Stripe complet est cash-only par défaut — la plupart des
        // scénarios e2e déposent un bid sans préciser de méthode (défaut STRIPE)
        // et n'ont pas vocation à tester ce cas-là. On onboarde Stripe par défaut
        // ici ; les scénarios qui testent explicitement l'absence de carte
        // utilisent leurs propres steps dédiés (ex. TestDataSteps).
        jdbcTemplate.update(
                "UPDATE users SET stripe_account_status = 'ONBOARDING_COMPLETE', "
                        + "stripe_account_id = ? WHERE firebase_uid = ?",
                "acct_test_" + uid, uid);
        // Test travelers accept non-KYC-verified senders so bids aren't blocked by
        // the default contactKycOnly=true preference (real KYC isn't run in E2E).
        asCurrentUser().body(Map.of("contactKycOnly", false)).put("/auth/me/privacy-settings");
        ctx.setCurrentUser(uid, "ROLE_TRAVELER");
    }

    @Etantdonné("un utilisateur EXPÉDITEUR enregistré avec l'uid {string} et le téléphone {string}")
    public void givenRegisteredSender(String uid, String phone) {
        ctx.setCurrentUser(uid, "ROLE_SENDER");
        store(asCurrentUser().header("X-Test-Phone", phone).body(Map.of(
                "phoneNumber", phone,
                "roles", Set.of("SENDER")
        )).post("/auth/register"));
        ctx.setCurrentUser(uid, "ROLE_SENDER");
    }

    // ── When ──────────────────────────────────────────────────────────────────

    @Quand("je m'inscris avec le téléphone {string} et le rôle {string}")
    public void whenRegister(String phone, String role) {
        Response resp = asCurrentUser().header("X-Test-Phone", phone).body(Map.of(
                "phoneNumber", phone,
                "roles", Set.of(role)
        )).post("/auth/register");
        store(resp);
    }

    @Quand("je m'inscris avec le téléphone {string} et les rôles {string} et {string}")
    public void whenRegisterBothRoles(String phone, String role1, String role2) {
        Response resp = asCurrentUser().header("X-Test-Phone", phone).body(Map.of(
                "phoneNumber", phone,
                "roles", Set.of(role1, role2)
        )).post("/auth/register");
        store(resp);
    }

    @Quand("je consulte mon profil")
    public void whenGetProfile() {
        store(asCurrentUser().get("/auth/me"));
    }

    @Quand("je mets à jour mon profil avec le prénom {string} et le nom {string}")
    public void whenUpdateProfile(String firstName, String lastName) {
        store(asCurrentUser().body(Map.of(
                "firstName", firstName,
                "lastName", lastName
        )).patch("/auth/me"));
    }

    @Quand("je supprime mon compte")
    public void whenDeleteAccount() {
        store(asCurrentUser().delete("/auth/me"));
    }

    // ── Then ──────────────────────────────────────────────────────────────────

    @Alors("la réponse contient un identifiant utilisateur")
    public void thenResponseHasUserId() {
        String id = lastResponse().jsonPath().getString("id");
        Assertions.assertThat(id).isNotBlank();
    }

    @Alors("la réponse contient le rôle {string}")
    public void thenResponseHasRole(String role) {
        List<String> roles = lastResponse().jsonPath().getList("roles");
        Assertions.assertThat(roles).contains(role);
    }

    @Alors("le profil contient le prénom {string} et le nom {string}")
    public void thenProfileHasName(String firstName, String lastName) {
        Assertions.assertThat(lastResponse().jsonPath().getString("firstName")).isEqualTo(firstName);
        Assertions.assertThat(lastResponse().jsonPath().getString("lastName")).isEqualTo(lastName);
    }

}
