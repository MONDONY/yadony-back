package com.yadony.api.kyc.provider.didit;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DiditClientTest {

    private static final String CALLBACK = "https://yadony.com/kyc/complete";

    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final DiditClient client = new DiditClient(
            builder.build(),
            new DiditProperties("https://verification.didit.me", "cle", "wf_1", "secret"));

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

        JsonNode response = client.createSession(userId, CALLBACK);

        assertThat(response.path("session_id").asText()).isEqualTo("sess_1");
        assertThat(response.path("url").asText()).isEqualTo("https://verify.didit.me/fr/session/tok");
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

        client.createSession(userId, CALLBACK);

        server.verify();
    }

    @Test
    void createSession_throws_whenDiditFails() {
        server.expect(requestTo("https://verification.didit.me/v3/session/"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.createSession(UUID.randomUUID(), CALLBACK))
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
}
