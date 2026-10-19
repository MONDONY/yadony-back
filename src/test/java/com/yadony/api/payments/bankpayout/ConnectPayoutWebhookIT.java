package com.yadony.api.payments.bankpayout;

import com.stripe.net.Webhook;
import com.yadony.api.common.stripe.StripeEventDispatcher;
import com.yadony.api.common.stripe.StripeEventInboxRepository;
import com.yadony.api.payments.events.BankPayoutPaidEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Webhook {@code payout.paid} des comptes connectés, signé avec le secret de l'endpoint Connect,
 * de bout en bout : signature → inbox (idempotente) → dispatch → suivi du virement → événement de
 * notification.
 */
@SpringBootTest(properties = "stripe.webhook.connect-secret=whsec_test_connect")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@RecordApplicationEvents
class ConnectPayoutWebhookIT {

    static final String SECRET = "whsec_test_connect";

    @Autowired MockMvc mockMvc;
    @Autowired StripeEventInboxRepository inbox;
    @Autowired StripeEventDispatcher dispatcher;
    @Autowired BankPayoutTrackingRepository repository;
    @Autowired ApplicationEvents applicationEvents;
    @MockBean BankPayoutStripeGateway gateway;

    private static String sign(String payload) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        String signature = Webhook.Util.computeHmacSha256(SECRET, timestamp + "." + payload);
        return "t=" + timestamp + ",v1=" + signature;
    }

    @Test
    void signedConnectPayoutPaid_marksTheParcelPaid_once() throws Exception {
        String account = "acct_it_" + UUID.randomUUID().toString().substring(0, 8);
        String py = "py_it_" + UUID.randomUUID().toString().substring(0, 8);
        String payoutId = "po_it_" + UUID.randomUUID().toString().substring(0, 8);
        BankPayoutTrackingEntity row = new BankPayoutTrackingEntity();
        row.setPaymentId(UUID.randomUUID());
        row.setTravelerId(UUID.randomUUID());
        row.setTransferId("tr_it");
        row.setConnectedAccountId(account);
        row.setDestinationPaymentId(py);
        row.setAvailableOn(LocalDate.now().minusDays(1));
        repository.saveAndFlush(row);
        when(gateway.payoutBalanceTransactions(account, payoutId))
                .thenReturn(List.of(new BankPayoutStripeGateway.BalanceTxnRef("payment", py)));

        long arrival = LocalDate.now(java.time.ZoneOffset.UTC).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond();
        String eventId = "evt_it_" + UUID.randomUUID();
        String payload = "{\"id\":\"" + eventId + "\",\"object\":\"event\",\"type\":\"payout.paid\","
                + "\"account\":\"" + account + "\",\"created\":" + Instant.now().getEpochSecond() + ","
                + "\"data\":{\"object\":{\"id\":\"" + payoutId + "\",\"object\":\"payout\",\"status\":\"paid\","
                + "\"amount\":500,\"currency\":\"eur\",\"arrival_date\":" + arrival + ",\"automatic\":true}}}";

        // Signature invalide : refusée, rien en inbox.
        mockMvc.perform(post("/payments/webhook").header("Stripe-Signature", "t=1,v1=bad")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest());
        assertThat(inbox.existsById(eventId)).isFalse();

        // Signée avec le secret Connect : acceptée ; rejouée : 200 et toujours une seule ligne.
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/payments/webhook").header("Stripe-Signature", sign(payload))
                            .contentType(MediaType.APPLICATION_JSON).content(payload))
                    .andExpect(status().isOk());
        }
        assertThat(inbox.findById(eventId)).isPresent();

        assertThat(dispatcher.dispatch(inbox.findById(eventId).orElseThrow().getPayload())).isTrue();
        assertThat(dispatcher.dispatch(inbox.findById(eventId).orElseThrow().getPayload())).isTrue();

        BankPayoutTrackingEntity saved = repository.findByPaymentId(row.getPaymentId()).orElseThrow();
        assertThat(saved.getPayoutId()).isEqualTo(payoutId);
        assertThat(saved.getPayoutStatus()).isEqualTo("paid");
        assertThat(saved.getPaidAt()).isNotNull();
        assertThat(saved.getArrivalDate()).isEqualTo(LocalDate.now(java.time.ZoneOffset.UTC));
        assertThat(applicationEvents.stream(BankPayoutPaidEvent.class)
                .filter(e -> payoutId.equals(e.payoutId()))).hasSize(1);
    }
}
