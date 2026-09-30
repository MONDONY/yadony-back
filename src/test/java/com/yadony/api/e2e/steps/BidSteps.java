package com.yadony.api.e2e.steps;

import io.cucumber.java.fr.Alors;
import io.cucumber.java.fr.Etantdonné;
import io.cucumber.java.fr.Quand;
import io.restassured.response.Response;
import org.assertj.core.api.Assertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class BidSteps extends AbstractSteps {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ── Given ─────────────────────────────────────────────────────────────────

    /**
     * Simulates a completed sender payment by moving the bid to PAYMENT_ESCROWED.
     * In production this happens via Stripe checkout/escrow; E2E has no real Stripe,
     * and acceptBid() requires PAYMENT_ESCROWED — so this is the test bridge between
     * bid creation and traveler acceptance.
     */
    @Etantdonné("le paiement de l'offre {string} est validé")
    public void givenBidPaymentEscrowed(String bidAlias) {
        jdbcTemplate.update("UPDATE bids SET status = 'PAYMENT_ESCROWED' WHERE id = ?",
                ctx.getId(bidAlias));
    }

    @Etantdonné("il existe une offre acceptée sur l'annonce {string} sauvegardée sous {string}")
    public void givenAcceptedBid(String announcementAlias, String bidAlias) {
        // Reuse whenCreateBid then acceptBid internally
        Response createResp = asCurrentUser()
                .body(buildBidBody(5.0))
                .post("/announcements/{id}/bids", ctx.getId(announcementAlias));
        createResp.then().statusCode(201);
        UUID bidId = UUID.fromString(createResp.jsonPath().getString("id"));
        ctx.saveId(bidAlias, bidId);

        // Switch to traveler role to accept
        String senderUid = ctx.getCurrentUid();
        String senderRoles = ctx.getCurrentRoles();
        ctx.setCurrentUser(ctx.getString("traveler-uid"), "ROLE_TRAVELER");
        asCurrentUser().put("/bids/{id}/accept", bidId).then().statusCode(200);
        // Restore sender context
        ctx.setCurrentUser(senderUid, senderRoles);
    }

    // ── When ──────────────────────────────────────────────────────────────────

    // Le second paramètre {decimal} (déclaré en €) était historiquement declaredValueEur,
    // retiré du modèle (cf. suppression de la règle "valeur > 500€"). Conservé dans le
    // wording Gherkin (partagé par ~30 scénarios) mais ignoré côté body désormais.
    @Quand("je dépose une offre de {decimal} kg à {decimal} € sur l'annonce {string}")
    public void whenCreateBid(Double kg, Double value, String announcementAlias) {
        store(asCurrentUser()
                .body(buildBidBody(kg))
                .post("/announcements/{id}/bids", ctx.getId(announcementAlias)));
    }

    @Quand("je dépose une offre sans accepter le disclaimer sur l'annonce {string}")
    public void whenCreateBidWithoutDisclaimer(String announcementAlias) {
        Map<String, Object> body = buildBidBody(5.0);
        body.put("disclaimerSigned", false);
        store(asCurrentUser().body(body)
                .post("/announcements/{id}/bids", ctx.getId(announcementAlias)));
    }

    @Quand("j'accepte l'offre {string}")
    public void whenAcceptBid(String bidAlias) {
        store(asCurrentUser().put("/bids/{id}/accept", ctx.getId(bidAlias)));
        // Le voyageur ne reçoit plus le numéro avant la remise (FLUTTER-4R) : il est
        // lu en base, là où l'acceptation l'a généré.
        String tn = trackingNumberInDb(ctx.getId(bidAlias));
        if (tn != null) {
            ctx.saveString("tracking-number-" + bidAlias, tn);
        }
    }

    private String trackingNumberInDb(UUID bidId) {
        List<String> numbers = jdbcTemplate.queryForList(
                "SELECT tracking_number FROM bids WHERE id = ?", String.class, bidId);
        return numbers.isEmpty() ? null : numbers.get(0);
    }

    @Quand("je refuse l'offre {string} avec la raison {string}")
    public void whenRejectBid(String bidAlias, String reason) {
        store(asCurrentUser().body(Map.of("reason", reason))
                .put("/bids/{id}/reject", ctx.getId(bidAlias)));
    }

    @Quand("l'expéditeur annule l'offre {string}")
    public void whenSenderCancelBid(String bidAlias) {
        store(asCurrentUser().put("/bids/{id}/cancel", ctx.getId(bidAlias)));
    }

    @Quand("je consulte mes offres en tant qu'expéditeur")
    public void whenGetMyBids() {
        store(asCurrentUser().get("/bids/me"));
    }

    @Quand("je consulte les offres de l'annonce {string}")
    public void whenGetBidsForAnnouncement(String announcementAlias) {
        store(asCurrentUser().get("/announcements/{id}/bids", ctx.getId(announcementAlias)));
    }

    @Quand("je sauvegarde l'id de l'offre de la réponse sous {string}")
    public void whenSaveBidId(String alias) {
        String idStr = lastResponse().jsonPath().getString("id");
        ctx.saveId(alias, UUID.fromString(idStr));
    }

    // ── Then ──────────────────────────────────────────────────────────────────

    @Alors("le statut de l'offre est {string}")
    public void thenBidStatus(String status) {
        String actual = lastResponse().jsonPath().getString("status");
        Assertions.assertThat(actual).isEqualTo(status);
    }

    @Alors("l'offre a un numéro de suivi")
    public void thenBidHasTrackingNumber() {
        UUID bidId = UUID.fromString(lastResponse().jsonPath().getString("id"));
        Assertions.assertThat(trackingNumberInDb(bidId)).startsWith("DON-");
    }

    @Alors("le numéro de suivi n'est pas communiqué au voyageur")
    public void thenTrackingNumberHiddenFromTraveler() {
        Assertions.assertThat(lastResponse().jsonPath().getString("trackingNumber")).isNull();
        Assertions.assertThat(lastResponse().jsonPath().getString("trackingToken")).isNull();
    }

    @Alors("la réponse contient {int} offre(s)")
    public void thenResponseContainsNBids(int count) {
        List<?> content = lastResponse().jsonPath().getList("$");
        Assertions.assertThat(content).hasSize(count);
    }

    @Alors("l'offre {string} est sauvegardée")
    public void thenBidSaved(String alias) {
        UUID id = UUID.fromString(lastResponse().jsonPath().getString("id"));
        ctx.saveId(alias, id);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> buildBidBody(Double kg) {
        Map<String, Object> body = new HashMap<>();
        body.put("weightKg", kg);
        body.put("description", "Médicaments pour la famille");
        body.put("contentCategory", "Médicaments");
        body.put("recipientName", "Fatou Diallo");
        body.put("recipientPhone", "+221771234567");
        body.put("disclaimerSigned", true);
        return body;
    }
}
