package com.yadony.api.common.stripe;

import com.yadony.api.common.YadonyBusinessException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StripeWebhookIngestService {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookIngestService.class);

    private final StripeEventInboxRepository repo;
    private final String paymentsSecret;
    private final String kycSecret;
    private final String billingSecret;

    public StripeWebhookIngestService(
            StripeEventInboxRepository repo,
            @Qualifier("stripePaymentsWebhookSecret") String paymentsSecret,
            @Qualifier("stripeKycWebhookSecret") String kycSecret,
            @Qualifier("stripeBillingWebhookSecret") String billingSecret) {
        this.repo = repo;
        this.paymentsSecret = paymentsSecret;
        this.kycSecret = kycSecret;
        this.billingSecret = billingSecret;
    }

    @Transactional
    public void ingest(String payload, String sigHeader, StripeWebhookSource source) {
        // switch exhaustif, et non un ternaire : une nouvelle source ajoutée à
        // l'enum sans être traitée ici validerait sa signature avec le secret
        // d'une autre source, silencieusement.
        String secret = switch (source) {
            case PAYMENTS -> paymentsSecret;
            case KYC -> kycSecret;
            case BILLING -> billingSecret;
        };
        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, secret);
        } catch (SignatureVerificationException e) {
            log.warn("Invalid Stripe webhook signature for source={}: {}", source, e.getMessage());
            throw new YadonyBusinessException(HttpStatus.BAD_REQUEST,
                    "invalid-webhook-signature", "Webhook Error", "Signature webhook invalide");
        }

        if (repo.existsById(event.getId())) {
            log.info("Stripe event {} already in inbox — skipping duplicate", event.getId());
            return;
        }

        var inbox = new StripeEventInbox(event.getId(), source, event.getType(), payload);
        repo.save(inbox);
        log.info("Stripe event {} ({}) ingested from {}", event.getId(), event.getType(), source);
    }
}
