package com.yadony.api.e2e.steps;

import io.cucumber.java.fr.Alors;
import io.cucumber.java.fr.Etantdonné;
import org.assertj.core.api.Assertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Cross-cutting test-data provisioning + generic assertions shared by the
 * feature-specific step classes.
 *
 * <p>The E2E run has no real Stripe / KYC provider, yet several flows hard-require
 * a VERIFIED KYC status or a PRO account (independent of the {@code Yadony.*.enforce}
 * flags). These steps are the SQL bridge that simulates those external states, the
 * same way {@code BidSteps} bridges a bid to PAYMENT_ESCROWED.
 */
public class TestDataSteps extends AbstractSteps {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ── Account state bridges ─────────────────────────────────────────────────

    @Etantdonné("le KYC de {string} est vérifié")
    public void givenKycVerified(String uid) {
        // stripe_account_id is UNIQUE, so derive it from the uid (the e2e StripeGateway mock
        // returns the same stub Account for any id, so the exact value is irrelevant).
        jdbcTemplate.update(
                "UPDATE users SET kyc_status = 'VERIFIED', stripe_account_status = 'ONBOARDING_COMPLETE', "
                        + "stripe_account_id = ? WHERE firebase_uid = ?",
                "acct_test_" + uid, uid);
    }

    @Etantdonné("le compte {string} est un compte PRO")
    public void givenProAccount(String uid) {
        jdbcTemplate.update("UPDATE users SET is_pro_account = true WHERE firebase_uid = ?", uid);
        // PRO features (analytics, fiscal export) check the TRAVELER role in the DB,
        // not just the security context — registration only grants SENDER, so add it here.
        jdbcTemplate.update(
                "INSERT INTO user_roles (user_id, role) "
                        + "SELECT id, 'TRAVELER' FROM users WHERE firebase_uid = ? "
                        + "ON CONFLICT (user_id, role) DO NOTHING",
                uid);
    }

    @Etantdonné("l'identifiant du compte {string} est sauvegardé sous {string}")
    public void givenResolveUserId(String uid, String alias) {
        UUID id = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE firebase_uid = ?", UUID.class, uid);
        ctx.saveId(alias, id);
    }

    @Etantdonné("le compte Stripe du voyageur {string} est opérationnel")
    public void givenStripeOperational(String uid) {
        jdbcTemplate.update(
                "UPDATE users SET stripe_account_status = 'ONBOARDING_COMPLETE', "
                        + "stripe_account_id = ? WHERE firebase_uid = ?",
                "acct_test_" + uid, uid);
    }

    /**
     * Contrepartie de givenStripeOperational : annule l'onboarding Stripe par
     * défaut appliqué à l'inscription (AuthSteps.givenRegisteredTraveler),
     * pour les scénarios qui testent explicitement la création du compte
     * Connect depuis un état vierge.
     */
    @Etantdonné("le voyageur {string} n'a pas encore de compte Stripe")
    public void givenNoStripeAccount(String uid) {
        jdbcTemplate.update(
                "UPDATE users SET stripe_account_status = 'NOT_CREATED', "
                        + "stripe_account_id = NULL WHERE firebase_uid = ?",
                uid);
    }

    @Etantdonné("l'offre {string} est marquée comme livrée")
    public void givenBidCompleted(String bidAlias) {
        jdbcTemplate.update("UPDATE bids SET status = 'COMPLETED' WHERE id = ?", ctx.getId(bidAlias));
    }

    @Etantdonné("l'offre {string} est une remise cash dont l'horaire est dépassé")
    public void givenCashHandoverOverdue(String bidAlias) {
        // No-show is only reportable for an accepted CASH bid whose handover window
        // has already passed — bridge the bid into that exact state.
        jdbcTemplate.update(
                "UPDATE bids SET payment_method = 'CASH', status = 'ACCEPTED', "
                        + "handover_deadline = CURRENT_TIMESTAMP - INTERVAL '2 hours' WHERE id = ?",
                ctx.getId(bidAlias));
    }

    @Etantdonné("le jeton de suivi de l'offre {string} est mémorisé")
    public void givenTrackingTokenMemorized(String bidAlias) {
        String token = jdbcTemplate.queryForObject(
                "SELECT tracking_token FROM bids WHERE id = ?", String.class, ctx.getId(bidAlias));
        ctx.saveString("tracking-token", token);
    }

    // ── Generic response assertions ───────────────────────────────────────────

    @Alors("la réponse est une liste de {int} élément(s)")
    public void thenResponseListOfSize(int size) {
        List<?> list = lastResponse().jsonPath().getList("$");
        Assertions.assertThat(list).hasSize(size);
    }

    @Alors("la réponse est une liste non vide")
    public void thenResponseListNotEmpty() {
        List<?> list = lastResponse().jsonPath().getList("$");
        Assertions.assertThat(list).isNotEmpty();
    }

    @Alors("la réponse contient le champ {string}")
    public void thenResponseHasField(String field) {
        Object value = lastResponse().jsonPath().get(field);
        Assertions.assertThat(value).as("field %s", field).isNotNull();
    }

    @Alors("le champ {string} de la réponse vaut {string}")
    public void thenResponseFieldEquals(String field, String expected) {
        String actual = lastResponse().jsonPath().getString(field);
        Assertions.assertThat(actual).isEqualTo(expected);
    }

    @Alors("le champ {string} de la réponse est vrai")
    public void thenResponseFieldTrue(String field) {
        Boolean actual = lastResponse().jsonPath().getBoolean(field);
        Assertions.assertThat(actual).isTrue();
    }
}
