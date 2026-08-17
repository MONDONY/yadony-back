package com.yadony.api.e2e.steps;

import io.cucumber.java.fr.Alors;
import io.cucumber.java.fr.Etantdonné;
import io.cucumber.java.fr.Quand;
import org.assertj.core.api.Assertions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TrackingSteps extends AbstractSteps {

    // ── When ──────────────────────────────────────────────────────────────────

    @Quand("je recherche le colis avec le numéro {string}")
    public void whenSearchByTrackingNumber(String number) {
        // /tracking/search exige une authentification : l'endpoint expose le statut du
        // colis et l'adresse de retrait, réservés à l'expéditeur et au voyageur.
        store(asCurrentUser().queryParam("number", number).get("/tracking/search"));
    }

    @Quand("je recherche le colis avec le numéro de suivi de l'offre {string}")
    public void whenSearchByTrackingNumberFromBid(String bidAlias) {
        // First get the bid to retrieve the tracking number
        String trackingNumber = ctx.getString("tracking-number-" + bidAlias);
        // /tracking/search contrôle désormais la propriété du colis : la recherche doit
        // être faite par l'expéditeur ou le voyageur, pas en anonyme.
        store(asCurrentUser().queryParam("number", trackingNumber).get("/tracking/search"));
    }

    @Quand("je scanne un événement {string} sur l'offre {string}")
    public void whenScanEvent(String eventType, String bidAlias) {
        Map<String, Object> body = new HashMap<>();
        body.put("bidId", ctx.getId(bidAlias).toString());
        body.put("eventType", eventType);
        body.put("gpsLat", 48.8566);
        body.put("gpsLon", 2.3522);
        store(asCurrentUser().body(body).post("/tracking/events"));
    }

    @Quand("le voyageur confirme la livraison de l'offre {string} avec le code {string}")
    public void whenConfirmDelivery(String bidAlias, String code) {
        store(asCurrentUser().body(Map.of("confirmationCode", code))
                .post("/tracking/{id}/confirm-delivery", ctx.getId(bidAlias)));
    }

    @Quand("le voyageur confirme la livraison de l'offre {string} avec le code sauvegardé")
    public void whenConfirmDeliveryWithSavedCode(String bidAlias) {
        String code = ctx.getString("confirmation-code-" + bidAlias);
        store(asCurrentUser().body(Map.of("confirmationCode", code))
                .post("/tracking/{id}/confirm-delivery", ctx.getId(bidAlias)));
    }

    @Quand("l'expéditeur récupère le code de confirmation de l'offre {string}")
    public void whenGetConfirmationCode(String bidAlias) {
        store(asCurrentUser().get("/tracking/{id}/confirmation-code", ctx.getId(bidAlias)));
        String code = lastResponse().jsonPath().getString("confirmationCode");
        ctx.saveString("confirmation-code-" + bidAlias, code);
    }

    @Quand("je consulte les événements de l'offre {string}")
    public void whenGetEvents(String bidAlias) {
        store(asCurrentUser().get("/tracking/{id}/events", ctx.getId(bidAlias)));
    }

    @Quand("je consulte le QR code de l'offre {string}")
    public void whenGetQrCode(String bidAlias) {
        store(asCurrentUser().get("/tracking/{id}/qr-code", ctx.getId(bidAlias)));
    }

    @Quand("l'expéditeur régénère le code de confirmation de l'offre {string}")
    public void whenRefreshCode(String bidAlias) {
        store(asCurrentUser().post("/tracking/{id}/refresh-code", ctx.getId(bidAlias)));
    }

    @Quand("je tente de scanner un événement ARRIVEE sur l'offre {string}")
    public void whenScanArrival(String bidAlias) {
        Map<String, Object> body = new HashMap<>();
        body.put("bidId", ctx.getId(bidAlias).toString());
        body.put("eventType", "ARRIVEE");
        store(asCurrentUser().body(body).post("/tracking/events"));
    }

    // ── Then ──────────────────────────────────────────────────────────────────

    @Alors("la réponse contient l'étape courante {string}")
    public void thenCurrentStep(String step) {
        String actual = lastResponse().jsonPath().getString("currentStep");
        Assertions.assertThat(actual).isEqualTo(step);
    }

    @Alors("la réponse contient {int} événement(s) de suivi")
    public void thenTrackingEventsCount(int count) {
        List<?> events = lastResponse().jsonPath().getList("$");
        Assertions.assertThat(events).hasSize(count);
    }

    @Alors("la réponse contient le type d'événement {string}")
    public void thenEventType(String eventType) {
        String actual = lastResponse().jsonPath().getString("eventType");
        Assertions.assertThat(actual).isEqualTo(eventType);
    }

    @Alors("la réponse contient un code de confirmation à 6 chiffres")
    public void thenHasConfirmationCode() {
        String code = lastResponse().jsonPath().getString("confirmationCode");
        Assertions.assertThat(code).matches("\\d{6}");
    }
}
