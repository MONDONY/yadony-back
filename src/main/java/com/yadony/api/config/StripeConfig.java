package com.yadony.api.config;

import com.yadony.api.common.stripe.StripeWebhookProperties;
import com.stripe.Stripe;
import com.stripe.StripeClient;
import com.stripe.net.ApiResource;
import com.stripe.net.LiveStripeResponseGetter;
import com.yadony.api.common.stripe.ConnectionResetRetryingHttpClient;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({StripeConnectProperties.class, StripeWebhookProperties.class})
public class StripeConfig {

    @Value("${stripe.secret-key}")
    private String secretKey;

    /**
     * Relances réseau Stripe (YADONY-BACK-STAGING-17) : sans elles, un « Connection reset
     * by peer » transitoire remonte tel quel au premier essai. Le SDK relance les échecs de
     * connexion, les délais dépassés, les 409 et {@code Stripe-Should-Retry} ;
     * {@link ConnectionResetRetryingHttpClient} y ajoute la connexion réinitialisée, que le
     * SDK ne relance pas. Chaque POST garde la même clé d'idempotence d'une relance à
     * l'autre : la clé explicite de l'appelant, sinon celle que le SDK génère une fois.
     */
    @Value("${stripe.max-network-retries:2}")
    private int maxNetworkRetries;

    @Value("${stripe.webhook-secret:}")
    private String webhookSecret;

    @Value("${stripe.webhook.payments-secret:}")
    private String paymentsWebhookSecret;

    @Value("${stripe.webhook.kyc-secret:}")
    private String kycWebhookSecret;

    @Value("${stripe.webhook.billing-secret:}")
    private String billingWebhookSecret;

    @Value("${stripe.webhook.connect-secret:}")
    private String connectWebhookSecret;

    @PostConstruct
    public void init() {
        Stripe.apiKey = secretKey;
        Stripe.setMaxNetworkRetries(maxNetworkRetries);
        // Points d'entrée statiques (PaymentIntent.create, Refund.create…) : même client
        // que le StripeClient ci-dessous, qui relance aussi une connexion réinitialisée.
        ApiResource.setGlobalResponseGetter(
                new LiveStripeResponseGetter(new ConnectionResetRetryingHttpClient()));
    }

    /**
     * Client Stripe instancié, requis par l'API v2 : les points d'entrée statiques
     * ({@code Account.create}, etc.) n'exposent que la v1. Le reste du code continue
     * de passer par {@link Stripe#apiKey} posé ci-dessus — les deux cohabitent sur
     * la même clé.
     */
    @Bean
    public StripeClient stripeClient() {
        return StripeClient.builder()
                .setApiKey(secretKey)
                .setMaxNetworkRetries(maxNetworkRetries)
                .setHttpClient(new ConnectionResetRetryingHttpClient())
                .build();
    }

    @Bean("stripeWebhookSecret")
    public String stripeWebhookSecret() {
        return webhookSecret;
    }

    @Bean("stripePaymentsWebhookSecret")
    public String stripePaymentsWebhookSecret() {
        return paymentsWebhookSecret;
    }

    @Bean("stripeKycWebhookSecret")
    public String stripeKycWebhookSecret() {
        return kycWebhookSecret;
    }

    @Bean("stripeBillingWebhookSecret")
    public String stripeBillingWebhookSecret() {
        return billingWebhookSecret;
    }

    @Bean("stripeConnectWebhookSecret")
    public String stripeConnectWebhookSecret() {
        return connectWebhookSecret;
    }
}
