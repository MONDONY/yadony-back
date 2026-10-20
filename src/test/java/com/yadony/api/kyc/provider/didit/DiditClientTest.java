package com.yadony.api.kyc.provider.didit;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DiditClientTest {

    private static final String CALLBACK = "https://yadony.com/kyc/complete";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final DiditClient client = new DiditClient(
            builder.build(),
            new DiditProperties("https://verification.didit.me", "cle", "wf_1", "secret", "live"));

    @Test
    void createSession_sendsWorkflowVendorDataAndCallback() {
        UUID userId = UUID.randomUUID();
        server.expect(requestTo("https://verification.didit.me/v3/session/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-api-key", "cle"))
                .andExpect(jsonPath("$.workflow_id").value("wf_1"))
                .andExpect(jsonPath("$.vendor_data").value(userId.toString()))
                .andExpect(jsonPath("$.callback").value(CALLBACK))
                .andExpect(jsonPath("$.language").value("fr"))
                .andExpect(jsonPath("$.metadata.user_id").value(userId.toString()))
                .andRespond(withSuccess("""
                        {"session_id":"sess_1","url":"https://verify.didit.me/fr/session/tok","status":"Not Started"}
                        """, MediaType.APPLICATION_JSON));

        JsonNode response = client.createSession(userId, CALLBACK, "fr");

        assertThat(response.path("session_id").asText()).isEqualTo("sess_1");
        assertThat(response.path("url").asText()).isEqualTo("https://verify.didit.me/fr/session/tok");
        server.verify();
    }

    @Test
    void createSession_sendsTheGivenLanguage() {
        UUID userId = UUID.randomUUID();
        server.expect(requestTo("https://verification.didit.me/v3/session/"))
                .andExpect(jsonPath("$.language").value("en"))
                .andRespond(withSuccess("""
                        {"session_id":"sess_1","url":"https://verify.didit.me/en/session/tok"}
                        """, MediaType.APPLICATION_JSON));

        client.createSession(userId, CALLBACK, "en");

        server.verify();
    }

    /** Aucune donnée personnelle ne doit partir : Didit vérifie, yadony ne préjuge pas. */
    @Test
    void createSession_sendsNoPersonalData() {
        UUID userId = UUID.randomUUID();
        server.expect(requestTo("https://verification.didit.me/v3/session/"))
                .andExpect(jsonPath("$.contact_details").doesNotExist())
                .andExpect(jsonPath("$.expected_details").doesNotExist())
                .andRespond(withSuccess("""
                        {"session_id":"sess_1","url":"https://verify.didit.me/fr/session/tok"}
                        """, MediaType.APPLICATION_JSON));

        client.createSession(userId, CALLBACK, "fr");

        server.verify();
    }

    @Test
    void createSession_throws_whenDiditFails() {
        server.expect(requestTo("https://verification.didit.me/v3/session/"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.createSession(UUID.randomUUID(), CALLBACK, "fr"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void retrieveDecision_readsTheDecision() {
        server.expect(requestTo("https://verification.didit.me/v3/session/sess_1/decision/"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("x-api-key", "cle"))
                .andRespond(withSuccess("""
                        {"session_id":"sess_1","status":"Approved",
                         "id_verifications":[{"first_name":"Awa","last_name":"Diallo"}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.retrieveDecision("sess_1"))
                .get()
                .extracting(node -> node.path("status").asText())
                .isEqualTo("Approved");
        server.verify();
    }

    @Test
    void retrieveDecision_returnsEmpty_whenDiditFails() {
        server.expect(requestTo("https://verification.didit.me/v3/session/sess_1/decision/"))
                .andRespond(withServerError());

        assertThat(client.retrieveDecision("sess_1")).isEmpty();
    }

    @Test
    void createSession_avecVendorDataExplicite_lEnvoieTelQuel() {
        UUID userId = UUID.randomUUID();
        server.expect(requestTo("https://verification.didit.me/v3/session/"))
                .andExpect(jsonPath("$.vendor_data").value(userId + ":r2"))
                .andExpect(jsonPath("$.metadata.user_id").value(userId.toString()))
                .andRespond(withSuccess("""
                        {"session_id":"sess_2","url":"https://verify.didit.me/fr/session/tok2"}
                        """, MediaType.APPLICATION_JSON));

        JsonNode response = client.createSession(userId, CALLBACK, userId + ":r2", "fr");

        assertThat(response.path("session_id").asText()).isEqualTo("sess_2");
        server.verify();
    }

    private static final String SESSION = "11111111-2222-3333-4444-555555555555";
    private static final String DELETE_URL = "https://verification.didit.me/v3/session/" + SESSION + "/delete/";

    @Test
    void deleteSession_envoieUnEffacementRgpdEtAccepteLe200Json() {
        server.expect(requestTo(DELETE_URL))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("x-api-key", "cle"))
                .andExpect(jsonPath("$.deletion_instruction").value("privacy_erasure"))
                .andExpect(jsonPath("$.instruction_id").value("user-deletion-42"))
                .andRespond(withSuccess("""
                        {"session_id":"%s","session_number":1024,"face_retention_outcome":"deleted"}
                        """.formatted(SESSION), MediaType.APPLICATION_JSON));

        DiditClient.DeletionOutcome outcome = client.deleteSession(SESSION, "user-deletion-42");

        assertThat(outcome.status()).isEqualTo(DiditClient.DeletionOutcome.Status.DELETED);
        assertThat(outcome.erased()).isTrue();
        assertThat(outcome.httpStatus()).isNull();
        server.verify();
    }

    /** Ancien contrat de Didit : 204 sans corps. Toujours accepté. */
    @Test
    void deleteSession_accepteEncoreLe204() {
        server.expect(requestTo(DELETE_URL)).andRespond(withNoContent());

        assertThat(client.deleteSession(SESSION, "i").status())
                .isEqualTo(DiditClient.DeletionOutcome.Status.DELETED);
    }

    @Test
    void deleteSession_404_vautDejaEfface() {
        server.expect(requestTo(DELETE_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        DiditClient.DeletionOutcome outcome = client.deleteSession(SESSION, "i");

        assertThat(outcome.status()).isEqualTo(DiditClient.DeletionOutcome.Status.ALREADY_DELETED);
        assertThat(outcome.erased()).isTrue();
    }

    @Test
    void deleteSession_403_echecAvecLeStatut() {
        server.expect(requestTo(DELETE_URL)).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .body("{\"detail\":\"permission\"}").contentType(MediaType.APPLICATION_JSON));

        DiditClient.DeletionOutcome outcome = client.deleteSession(SESSION, "i");

        assertThat(outcome.status()).isEqualTo(DiditClient.DeletionOutcome.Status.FAILED);
        assertThat(outcome.httpStatus()).isEqualTo(403);
        assertThat(outcome.erased()).isFalse();
    }

    @Test
    void deleteSession_503_echecRejouable() {
        server.expect(requestTo(DELETE_URL)).andRespond(withServerError());

        assertThat(client.deleteSession(SESSION, "i").httpStatus()).isEqualTo(500);
    }

    @Test
    void deleteSession_reseauCoupe_echecSansStatut() {
        server.expect(requestTo(DELETE_URL)).andRespond(withException(new IOException("connexion refusée")));

        DiditClient.DeletionOutcome outcome = client.deleteSession(SESSION, "i");

        assertThat(outcome.status()).isEqualTo(DiditClient.DeletionOutcome.Status.FAILED);
        assertThat(outcome.httpStatus()).isNull();
    }
}
