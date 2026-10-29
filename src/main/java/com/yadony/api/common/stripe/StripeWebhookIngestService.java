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
    /**
     * Secret de l'endpoint « comptes connectés ». Un endpoint Stripe écoute soit le compte
     * plateforme, soit les comptes connectés, jamais les deux : les événements des comptes
     * voyageurs (account.updated, capability.updated, account.application.deauthorized,
     * payout.*) arrivent donc par un second endpoint, sur la même URL que les paiements,
     * signé avec son propre secret. Vide = second endpoint non déclaré.
     */
    private final String connectSecret;

    public StripeWebhookIngestService(
            StripeEventInboxRepository repo,
            @Qualifier("stripePaymentsWebhookSecret") String paymentsSecret,
            @Qualifier("stripeKycWebhookSecret") String kycSecret,
            @Qualifier("stripeBillingWebhookSecret") String billingSecret,
            @Qualifier("stripeConnectWebhookSecret") String connectSecret) {
        this.repo = repo;
        this.paymentsSecret = paymentsSecret;
        this.kycSecret = kycSecret;
        this.billingSecret = billingSecret;
        this.connectSecret = connectSecret;
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
            event = source == StripeWebhookSource.PAYMENTS ? constructWithConnectSecret(payload, sigHeader) : null;
            if (event == null) {
                log.warn("Invalid Stripe webhook signature for source={}: {}", source, e.getMessage());
                throw new YadonyBusinessException(HttpStatus.BAD_REQUEST,
                        "invalid-webhook-signature", "Webhook Error", "Signature webhook invalide");
            }
            // Signé par l'endpoint des comptes connectés : il ne porte que la vie des comptes
            // voyageurs et leurs virements. Tout autre type (un paiement encaissé par le voyageur
            // lui-même, par exemple) est écarté sans être stocké ; 200 pour que Stripe ne le
            // rejoue pas.
            if (!StripeConnectEvents.fromConnectedAccount(event)
                    || !StripeConnectEvents.isConnectedAccountType(event.getType())) {
                log.warn("Stripe event {} ({}, account={}) signé par l'endpoint Connect : type non attendu, ignoré",
                        event.getId(), event.getType(), event.getAccount());
                return;
            }
        }

        if (repo.existsById(event.getId())) {
            log.info("Stripe event {} already in inbox — skipping duplicate", event.getId());
            return;
        }

        var inbox = new StripeEventInbox(event.getId(), source, event.getType(), payload);
        repo.save(inbox);
        log.info("Stripe event {} ({}) ingested from {}", event.getId(), event.getType(), source);
    }

    /** Seconde chance pour la source PAYMENTS : l'endpoint des comptes connectés. */
    private Event constructWithConnectSecret(String payload, String sigHeader) {
        if (connectSecret == null || connectSecret.isBlank()) {
            return null;
        }
        try {
            return Webhook.constructEvent(payload, sigHeader, connectSecret);
        } catch (SignatureVerificationException e) {
            return null;
        }
    }
}
