package com.yadony.api.payments.pawapay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.payments.pawapay.dto.PawapayDepositRequest;
import com.yadony.api.payments.pawapay.dto.PawapayInitiationResult;
import com.yadony.api.payments.pawapay.dto.PawapayOperationSnapshot;
import com.yadony.api.payments.pawapay.dto.PawapayPayoutRequest;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig;
import com.yadony.api.payments.pawapay.dto.PawapayRefundRequest;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class PawapayClientTest {

    private static final String BASE = "https://pawapay-stub.test";
    private MockRestServiceServer server;
    private PawapayClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new PawapayClient(builder.build(), new ObjectMapper());
    }

    @Test
    void initiateDeposit_sendsV2Body_andParsesAccepted() {
        UUID id = UUID.randomUUID();
        server.expect(requestTo(BASE + "/v2/deposits"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.depositId").value(id.toString()))
                .andExpect(jsonPath("$.amount").value("15000"))
                .andExpect(jsonPath("$.currency").value("XOF"))
                .andExpect(jsonPath("$.payer.type").value("MMO"))
                .andExpect(jsonPath("$.payer.accountDetails.phoneNumber").value("221771234567"))
                .andExpect(jsonPath("$.payer.accountDetails.provider").value("ORANGE_SEN"))
                .andExpect(jsonPath("$.customerMessage").value("yadony envoi"))
                .andExpect(jsonPath("$.successfulUrl").value("https://api.test/ok"))
                .andExpect(jsonPath("$.failedUrl").value("https://api.test/ko"))
                .andRespond(withSuccess("{\"depositId\":\"" + id + "\",\"status\":\"ACCEPTED\",\"created\":\"2026-09-04T10:00:00Z\"}",
                        MediaType.APPLICATION_JSON));

        PawapayInitiationResult result = client.initiateDeposit(new PawapayDepositRequest(id, "221771234567",
                "ORANGE_SEN", new BigDecimal("15000"), "XOF", "yadony envoi", "bid-1",
                "https://api.test/ok", "https://api.test/ko"));

        assertThat(result.outcome()).isEqualTo(PawapayInitiationResult.Outcome.ACCEPTED);
        server.verify();
    }

    @Test
    void initiateDeposit_omitsRedirectUrlsWhenNull() {
        UUID id = UUID.randomUUID();
        server.expect(requestTo(BASE + "/v2/deposits"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("successfulUrl"))))
                .andRespond(withSuccess("{\"depositId\":\"" + id + "\",\"status\":\"ACCEPTED\"}", MediaType.APPLICATION_JSON));

        client.initiateDeposit(new PawapayDepositRequest(id, "221771234567", "ORANGE_SEN",
                new BigDecimal("15000"), "XOF", "yadony envoi", null, null, null));
        server.verify();
    }

    @Test
    void initiatePayout_rejected_isParsedEvenOn400() {
        UUID id = UUID.randomUUID();
        server.expect(requestTo(BASE + "/v2/payouts"))
                .andExpect(jsonPath("$.recipient.accountDetails.provider").value("WAVE_SEN"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"payoutId\":\"" + id + "\",\"status\":\"REJECTED\",\"failureReason\":{\"failureCode\":\"INSUFFICIENT_BALANCE\",\"failureMessage\":\"no funds\"}}"));

        PawapayInitiationResult result = client.initiatePayout(new PawapayPayoutRequest(id, "221771234567",
                "WAVE_SEN", new BigDecimal("13200"), "XOF", "yadony versement", null));

        assertThat(result.outcome()).isEqualTo(PawapayInitiationResult.Outcome.REJECTED);
        assertThat(result.failureCode()).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(result.failureMessage()).isEqualTo("no funds");
    }

    @Test
    void initiateRefund_duplicateIgnored() {
        UUID refundId = UUID.randomUUID();
        UUID depositId = UUID.randomUUID();
        server.expect(requestTo(BASE + "/v2/refunds"))
                .andExpect(jsonPath("$.refundId").value(refundId.toString()))
                .andExpect(jsonPath("$.depositId").value(depositId.toString()))
                .andExpect(jsonPath("$.amount").value("15000"))
                .andRespond(withSuccess("{\"refundId\":\"" + refundId + "\",\"status\":\"DUPLICATE_IGNORED\"}", MediaType.APPLICATION_JSON));

        assertThat(client.initiateRefund(new PawapayRefundRequest(refundId, depositId, new BigDecimal("15000"), "XOF")).outcome())
                .isEqualTo(PawapayInitiationResult.Outcome.DUPLICATE_IGNORED);
    }

    @Test
    void getStatus_found_mapsDataBlock_includingAuthorizationUrl() {
        UUID id = UUID.randomUUID();
        server.expect(requestTo(BASE + "/v2/deposits/" + id)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"status\":\"FOUND\",\"data\":{\"depositId\":\"" + id + "\",\"status\":\"FAILED\","
                        + "\"providerTransactionId\":\"ptx\",\"authorizationUrl\":\"https://wave/auth\","
                        + "\"failureReason\":{\"failureCode\":\"PAYMENT_NOT_APPROVED\",\"failureMessage\":\"declined\"}}}",
                        MediaType.APPLICATION_JSON));

        PawapayOperationSnapshot snap = client.getStatus(PawapayOperationKind.DEPOSIT, id).orElseThrow();
        assertThat(snap.status()).isEqualTo(PawapayOperationStatus.FAILED);
        assertThat(snap.failureCode()).isEqualTo("PAYMENT_NOT_APPROVED");
        assertThat(snap.providerTransactionId()).isEqualTo("ptx");
        assertThat(snap.authorizationUrl()).isEqualTo("https://wave/auth");
        assertThat(snap.raw()).contains("PAYMENT_NOT_APPROVED");
    }

    @Test
    void getStatus_notFound_isEmpty() {
        UUID id = UUID.randomUUID();
        server.expect(requestTo(BASE + "/v2/payouts/" + id))
                .andRespond(withSuccess("{\"status\":\"NOT_FOUND\"}", MediaType.APPLICATION_JSON));
        assertThat(client.getStatus(PawapayOperationKind.PAYOUT, id)).isEmpty();
    }

    @Test
    void predictProvider_ok_andRejected() {
        server.expect(requestTo(BASE + "/v2/predict-provider")).andExpect(jsonPath("$.phoneNumber").value("+221771234567"))
                .andRespond(withSuccess("{\"country\":\"SEN\",\"provider\":\"ORANGE_SEN\",\"phoneNumber\":\"221771234567\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v2/predict-provider"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":\"REJECTED\",\"failureReason\":{\"failureCode\":\"INVALID_PHONE_NUMBER\",\"failureMessage\":\"bad\"}}"));

        assertThat(client.predictProvider("+221771234567")).get()
                .satisfies(p -> {
                    assertThat(p.countryAlpha3()).isEqualTo("SEN");
                    assertThat(p.provider()).isEqualTo("ORANGE_SEN");
                    assertThat(p.phoneNumber()).isEqualTo("221771234567");
                });
        assertThat(client.predictProvider("+33612345678")).isEmpty();
    }

    @Test
    void activeConfiguration_isFlattenedByProvider_andCached() {
        server.expect(requestTo(BASE + "/v2/active-conf")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"companyName":"yadony","countries":[{"country":"SEN","providers":[
                          {"provider":"ORANGE_SEN","currencies":[{"currency":"XOF","operationTypes":{
                             "DEPOSIT":{"minAmount":"100","maxAmount":"1500000","decimalsInAmount":"NONE","authType":"PROVIDER_AUTH","status":"OPERATIONAL"},
                             "PAYOUT":{"minAmount":"100","maxAmount":"1000000","decimalsInAmount":"NONE","status":"OPERATIONAL"}}}]},
                          {"provider":"WAVE_SEN","currencies":[{"currency":"XOF","operationTypes":{
                             "DEPOSIT":{"minAmount":"100","maxAmount":"1500000","decimalsInAmount":"NONE","authType":"REDIRECT_AUTH","status":"OPERATIONAL"}}}]}
                        ]}]}
                        """, MediaType.APPLICATION_JSON));

        Map<String, PawapayProviderConfig> conf = client.activeConfiguration();
        assertThat(conf).containsKeys("ORANGE_SEN", "WAVE_SEN");
        PawapayProviderConfig orange = conf.get("ORANGE_SEN");
        assertThat(orange.countryAlpha3()).isEqualTo("SEN");
        assertThat(orange.currency()).isEqualTo("XOF");
        assertThat(orange.supportsDeposit()).isTrue();
        assertThat(orange.supportsPayout()).isTrue();
        assertThat(orange.isRedirectDeposit()).isFalse();
        assertThat(orange.deposit().maxAmount()).isEqualByComparingTo("1500000");
        PawapayProviderConfig wave = conf.get("WAVE_SEN");
        assertThat(wave.supportsPayout()).isFalse();
        assertThat(wave.isRedirectDeposit()).isTrue();

        // second appel : servi par le cache, aucune requête supplémentaire attendue
        assertThat(client.activeConfiguration()).isSameAs(conf);
        server.verify();
    }

    @Test
    void walletBalances_andPublicKeys() {
        server.expect(requestTo(BASE + "/v2/wallet-balances"))
                .andRespond(withSuccess("{\"balances\":[{\"country\":\"SEN\",\"balance\":\"21798.03\",\"currency\":\"XOF\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/v2/public-key/http"))
                .andRespond(withSuccess("[{\"id\":\"HTTP_EC_P256_KEY:1\",\"key\":\"-----BEGIN PUBLIC KEY-----\\nAAA\\n-----END PUBLIC KEY-----\"}]", MediaType.APPLICATION_JSON));

        assertThat(client.walletBalances()).singleElement().satisfies(b -> {
            assertThat(b.countryAlpha3()).isEqualTo("SEN");
            assertThat(b.currency()).isEqualTo("XOF");
            assertThat(b.balance()).isEqualByComparingTo("21798.03");
        });
        assertThat(client.publicKeys()).singleElement().satisfies(k -> {
            assertThat(k.id()).isEqualTo("HTTP_EC_P256_KEY:1");
            assertThat(k.pem()).contains("BEGIN PUBLIC KEY");
        });
    }

    @Test
    void serverError_propagatesAsRestClientException() {
        server.expect(requestTo(BASE + "/v2/deposits")).andRespond(withServerError());
        assertThatThrownBy(() -> client.initiateDeposit(new PawapayDepositRequest(UUID.randomUUID(), "221771234567",
                "ORANGE_SEN", new BigDecimal("15000"), "XOF", "yadony envoi", null, null, null)))
                .isInstanceOf(RestClientException.class);
    }
}
