package com.yadony.api.e2e.steps;

import io.cucumber.java.fr.Alors;
import io.cucumber.java.fr.Quand;
import io.restassured.response.Response;
import org.assertj.core.api.Assertions;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Steps for the package-request + negotiation marketplace flow
 * (sender posts a package request → traveler negotiates → trip linked → awaiting payment).
 */
public class RequestsSteps extends AbstractSteps {

    private static final String DEPARTURE = "Paris";
    private static final String ARRIVAL = "Dakar";

    // ── Package requests ──────────────────────────────────────────────────────

    @Quand("je crée une demande de colis négociable sauvegardée sous {string}")
    public void whenCreateNegotiableRequest(String alias) {
        Response resp = asCurrentUser().body(buildRequestBody(true, 100.0)).post("/package-requests");
        store(resp);
        saveIfPresent(alias);
    }

    @Quand("je crée une demande de colis non négociable avec un budget de {decimal} € sauvegardée sous {string}")
    public void whenCreateNonNegotiableRequest(Double budget, String alias) {
        Response resp = asCurrentUser().body(buildRequestBody(false, budget)).post("/package-requests");
        store(resp);
        saveIfPresent(alias);
    }

    @Quand("je recherche les demandes de colis ouvertes")
    public void whenSearchOpenRequests() {
        store(asCurrentUser().get("/package-requests"));
    }

    @Quand("je recherche les demandes de colis au départ de {string}")
    public void whenSearchRequestsByDeparture(String city) {
        store(asCurrentUser().queryParam("departure", city).get("/package-requests"));
    }

    @Quand("je consulte mes demandes de colis")
    public void whenGetMyRequests() {
        store(asCurrentUser().get("/package-requests/me"));
    }

    @Quand("je consulte le détail de la demande {string}")
    public void whenGetRequestDetail(String alias) {
        store(asCurrentUser().get("/package-requests/{id}", ctx.getId(alias)));
    }

    @Quand("je consulte les fils de négociation de la demande {string}")
    public void whenGetRequestThreads(String alias) {
        store(asCurrentUser().get("/package-requests/{id}/threads", ctx.getId(alias)));
    }

    @Quand("j'estime le prix de {string} à {string} pour {decimal} kg")
    public void whenEstimatePrice(String from, String to, Double weight) {
        store(asCurrentUser()
                .queryParam("from", from)
                .queryParam("to", to)
                .queryParam("weight", weight)
                .get("/package-requests/estimate"));
    }

    @Quand("j'annule la demande de colis {string}")
    public void whenCancelRequest(String alias) {
        store(asCurrentUser().delete("/package-requests/{id}", ctx.getId(alias)));
    }

    @Quand("je complète les informations du destinataire de la demande {string}")
    public void whenCompleteDetails(String alias) {
        Map<String, Object> body = new HashMap<>();
        body.put("recipientName", "Fatou Diallo");
        body.put("recipientPhone", "+221771234567");
        body.put("recipientCity", "Dakar");
        store(asCurrentUser().body(body).post("/package-requests/{id}/complete-details", ctx.getId(alias)));
    }

    // ── Negotiations ──────────────────────────────────────────────────────────

    @Quand("je démarre une négociation sur la demande {string} avec un prix de {decimal} € sauvegardée sous {string}")
    public void whenStartNegotiation(String requestAlias, Double price, String threadAlias) {
        Map<String, Object> body = new HashMap<>();
        body.put("packageRequestId", ctx.getId(requestAlias).toString());
        body.put("proposedPriceEur", price);
        body.put("travelerTravelDate", LocalDate.now().plusDays(30).toString());
        body.put("travelerAvailableKg", 25.0);
        body.put("body", "J'ai de la place sur mon vol");
        // Trajet obligatoire dès l'offre (cf. spec 2026-08-16) : aucune annonce n'existe
        // encore à ce stade dans les scénarios e2e, donc on fournit systématiquement un
        // trajet dédié (createDedicatedTrip) plutôt qu'un travelerAnnouncementId.
        body.put("createDedicatedTrip", true);
        Map<String, Object> dedicatedTrip = new HashMap<>();
        dedicatedTrip.put("departureDate", LocalDate.now().plusDays(30).toString());
        dedicatedTrip.put("departureTime", "14:30");
        dedicatedTrip.put("arrivalTime", "20:15");
        dedicatedTrip.put("pickupAddress", Map.of("label", "Aéroport CDG", "lat", 49.0097, "lng", 2.5479));
        dedicatedTrip.put("deliveryAddress", Map.of("label", "Aéroport Dakar", "lat", 14.7397, "lng", -17.4902));
        dedicatedTrip.put("paymentMethod", "STRIPE");
        body.put("dedicatedTrip", dedicatedTrip);
        Response resp = asCurrentUser().body(body).post("/negotiations");
        store(resp);
        saveIfPresent(threadAlias);
    }

    @Quand("je fais une contre-offre de {decimal} € sur la négociation {string}")
    public void whenCounter(Double price, String threadAlias) {
        Map<String, Object> body = new HashMap<>();
        body.put("proposedPriceEur", price);
        body.put("body", "Contre-proposition");
        store(asCurrentUser().body(body).post("/negotiations/{id}/counter", ctx.getId(threadAlias)));
    }

    @Quand("j'accepte le prix de la négociation {string}")
    public void whenAcceptNegotiation(String threadAlias) {
        store(asCurrentUser().body(Map.of("body", "C'est bon pour moi"))
                .post("/negotiations/{id}/accept", ctx.getId(threadAlias)));
    }

    @Quand("je refuse la négociation {string}")
    public void whenRejectNegotiation(String threadAlias) {
        store(asCurrentUser().body(Map.of("reason", "Prix trop élevé"))
                .post("/negotiations/{id}/reject", ctx.getId(threadAlias)));
    }

    @Quand("je soumets le trajet {string} sur la négociation {string}")
    public void whenSubmitTrip(String announcementAlias, String threadAlias) {
        Map<String, Object> body = new HashMap<>();
        body.put("travelerAnnouncementId", ctx.getId(announcementAlias).toString());
        body.put("paymentMethod", "STRIPE");
        store(asCurrentUser().body(body).post("/negotiations/{id}/submit-trip", ctx.getId(threadAlias)));
    }

    @Quand("je crée un trajet dédié sur la négociation {string}")
    public void whenCreateDedicatedTrip(String threadAlias) {
        Map<String, Object> body = new HashMap<>();
        body.put("departureDate", LocalDate.now().plusDays(30).toString());
        body.put("departureTime", "14:30");
        body.put("arrivalTime", "20:15");
        body.put("pickupAddress", Map.of("label", "Aéroport CDG", "lat", 49.0097, "lng", 2.5479));
        body.put("deliveryAddress", Map.of("label", "Aéroport Dakar", "lat", 14.7397, "lng", -17.4902));
        body.put("paymentMethod", "STRIPE");
        store(asCurrentUser().body(body).post("/negotiations/{id}/create-dedicated-trip", ctx.getId(threadAlias)));
    }

    @Quand("je consulte mes négociations")
    public void whenGetMyNegotiations() {
        store(asCurrentUser().get("/negotiations/me"));
    }

    @Quand("je refuse le trajet de la négociation {string}")
    public void whenRefuseTrip(String threadAlias) {
        store(asCurrentUser().body(Map.of("reason", "Les horaires ne me conviennent pas"))
                .post("/negotiations/{id}/refuse-trip", ctx.getId(threadAlias)));
    }

    @Quand("j'initie le paiement séquestre de la négociation {string}")
    public void whenInitiateNegotiationPayment(String threadAlias) {
        store(asCurrentUser().post("/negotiations/{id}/initiate-payment", ctx.getId(threadAlias)));
    }

    @Quand("je consulte la négociation {string}")
    public void whenGetNegotiation(String threadAlias) {
        store(asCurrentUser().get("/negotiations/{id}", ctx.getId(threadAlias)));
    }

    // ── Then ──────────────────────────────────────────────────────────────────

    @Alors("le statut de la négociation est {string}")
    public void thenNegotiationStatus(String status) {
        Assertions.assertThat(lastResponse().jsonPath().getString("status")).isEqualTo(status);
    }

    @Alors("le statut de la demande est {string}")
    public void thenRequestStatus(String status) {
        Assertions.assertThat(lastResponse().jsonPath().getString("status")).isEqualTo(status);
    }

    @Alors("la réponse contient au moins {int} demande(s)")
    public void thenAtLeastNRequests(int count) {
        List<?> content = lastResponse().jsonPath().getList("content");
        Assertions.assertThat(content).hasSizeGreaterThanOrEqualTo(count);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void saveIfPresent(String alias) {
        String id = lastResponse().jsonPath().getString("id");
        if (id != null) {
            ctx.saveId(alias, UUID.fromString(id));
        }
    }

    private Map<String, Object> buildRequestBody(boolean negotiable, Double budget) {
        Map<String, Object> body = new HashMap<>();
        body.put("departureCity", DEPARTURE);
        body.put("arrivalCity", ARRIVAL);
        body.put("desiredDate", LocalDate.now().plusDays(30).toString());
        body.put("dateToleranceDays", 3);
        body.put("weightKg", 8.0);
        body.put("contentCategory", "Documents");
        body.put("description", "Documents administratifs");
        body.put("negotiable", negotiable);
        body.put("acceptedPaymentMethods", List.of("STRIPE"));
        if (budget != null) {
            body.put("totalBudgetEur", budget);
        }
        return body;
    }
}
