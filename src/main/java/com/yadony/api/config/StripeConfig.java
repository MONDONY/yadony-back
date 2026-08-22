package com.yadony.api.config;

import com.yadony.api.common.stripe.StripeWebhookProperties;
import com.stripe.Stripe;
import com.stripe.StripeClient;
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

    @Value("${stripe.webhook-secret:}")
    private String webhookSecret;

    @Value("${stripe.webhook.payments-secret:}")
    private String paymentsWebhookSecret;

    @Value("${stripe.webhook.kyc-secret:}")
    private String kycWebhookSecret;

    @PostConstruct
    public void init() {
        Stripe.apiKey = secretKey;
    }

    /**
     * Client Stripe instancié, requis par l'API v2 : les points d'entrée statiques
     * ({@code Account.create}, etc.) n'exposent que la v1. Le reste du code continue
     * de passer par {@link Stripe#apiKey} posé ci-dessus — les deux cohabitent sur
     * la même clé.
     */
    @Bean
    public StripeClient stripeClient() {
        return new StripeClient(secretKey);
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
}
