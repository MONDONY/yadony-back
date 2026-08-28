package com.yadony.api.common.stripe;

import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StripeWebhookIngestService — chaque source valide avec son propre secret")
class StripeWebhookIngestServiceTest {

    private static final String PAYMENTS_SECRET = "whsec_payments_aaaaaaaaaaaaaaaa";
    private static final String KYC_SECRET = "whsec_kyc_bbbbbbbbbbbbbbbbbbbb";
    private static final String BILLING_SECRET = "whsec_billing_cccccccccccccccc";

    @Mock StripeEventInboxRepository repo;

    private StripeWebhookIngestService service() {
        return new StripeWebhookIngestService(repo, PAYMENTS_SECRET, KYC_SECRET, BILLING_SECRET);
    }

    /** Reproduit l'en-tête Stripe-Signature : t=<ts>,v1=<HMAC-SHA256(ts + "." + payload, secret)>. */
    private static String signature(String payload, String secret) {
        long timestamp = Instant.now().getEpochSecond();
        String signedPayload = timestamp + "." + payload;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return "t=" + timestamp + ",v1=" + hex;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String payload(String eventId, String type) {
        return "{\"id\":\"" + eventId + "\",\"object\":\"event\",\"type\":\"" + type
                + "\",\"data\":{\"object\":{}}}";
    }

    @Test
    @DisplayName("un événement billing signé avec le secret billing est accepté et rangé sous BILLING")
    void billingEventAcceptedWithBillingSecret() {
        String body = payload("evt_billing_1", "invoice.paid");
        when(repo.existsById("evt_billing_1")).thenReturn(false);

        service().ingest(body, signature(body, BILLING_SECRET), StripeWebhookSource.BILLING);

        ArgumentCaptor<StripeEventInbox> captor = ArgumentCaptor.forClass(StripeEventInbox.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getSource()).isEqualTo(StripeWebhookSource.BILLING);
        assertThat(captor.getValue().getEventType()).isEqualTo("invoice.paid");
    }

    @Test
    @DisplayName("un événement billing signé avec le secret payments est refusé")
    void billingEventRejectedWithPaymentsSecret() {
        String body = payload("evt_billing_2", "invoice.paid");

        assertThatThrownBy(() ->
                service().ingest(body, signature(body, PAYMENTS_SECRET), StripeWebhookSource.BILLING))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("Signature");

        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("un événement payments signé avec le secret billing est refusé")
    void paymentsEventRejectedWithBillingSecret() {
        String body = payload("evt_pay_1", "payment_intent.succeeded");

        assertThatThrownBy(() ->
                service().ingest(body, signature(body, BILLING_SECRET), StripeWebhookSource.PAYMENTS))
                .isInstanceOf(YadonyBusinessException.class);

        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("un événement KYC signé avec le secret KYC reste accepté")
    void kycEventStillWorks() {
        String body = payload("evt_kyc_1", "identity.verification_session.verified");
        when(repo.existsById("evt_kyc_1")).thenReturn(false);

        service().ingest(body, signature(body, KYC_SECRET), StripeWebhookSource.KYC);

        verify(repo).save(any(StripeEventInbox.class));
    }

    @Test
    @DisplayName("un événement déjà présent dans l'inbox n'est pas réinséré")
    void duplicateIsSkipped() {
        String body = payload("evt_dup", "invoice.paid");
        when(repo.existsById("evt_dup")).thenReturn(true);

        service().ingest(body, signature(body, BILLING_SECRET), StripeWebhookSource.BILLING);

        verify(repo, never()).save(any());
    }
}
