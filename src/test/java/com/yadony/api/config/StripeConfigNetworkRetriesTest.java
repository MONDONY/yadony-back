package com.yadony.api.config;

import com.stripe.Stripe;
import com.stripe.StripeClient;
import com.stripe.net.StripeResponseGetterOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * YADONY-BACK-STAGING-17 : un « Connection reset by peer » sur POST /bids/checkout n'était
 * jamais relancé, faute de {@code maxNetworkRetries}. Les deux points d'entrée du SDK (statique
 * et {@link StripeClient}) doivent porter le réglage {@code stripe.max-network-retries}.
 */
class StripeConfigNetworkRetriesTest {

    private String previousApiKey;
    private int previousRetries;
    private com.stripe.net.StripeResponseGetter previousGetter;

    @BeforeEach
    void saveGlobals() {
        previousApiKey = Stripe.apiKey;
        previousRetries = Stripe.getMaxNetworkRetries();
        previousGetter = com.stripe.net.ApiResource.getGlobalResponseGetter();
    }

    @AfterEach
    void restoreGlobals() {
        Stripe.apiKey = previousApiKey;
        Stripe.setMaxNetworkRetries(previousRetries);
        com.stripe.net.ApiResource.setGlobalResponseGetter(previousGetter);
    }

    private StripeConfig config(int retries) {
        StripeConfig config = new StripeConfig();
        ReflectionTestUtils.setField(config, "secretKey", "sk_test_dummy");
        ReflectionTestUtils.setField(config, "maxNetworkRetries", retries);
        return config;
    }

    @Test
    void init_configuresStaticSdkRetries() {
        config(2).init();

        assertThat(Stripe.getMaxNetworkRetries()).isEqualTo(2);
        Object getter = com.stripe.net.ApiResource.getGlobalResponseGetter();
        assertThat(ReflectionTestUtils.getField(getter, "httpClient"))
                .isInstanceOf(com.yadony.api.common.stripe.ConnectionResetRetryingHttpClient.class);
    }

    @Test
    void stripeClient_carriesTheSameRetries() {
        StripeClient client = config(3).stripeClient();

        Object getter = ReflectionTestUtils.getField(client, "responseGetter");
        StripeResponseGetterOptions options =
                (StripeResponseGetterOptions) ReflectionTestUtils.getField(getter, "options");
        assertThat(options.getMaxNetworkRetries()).isEqualTo(3);
        assertThat(ReflectionTestUtils.getField(getter, "httpClient"))
                .isInstanceOf(com.yadony.api.common.stripe.ConnectionResetRetryingHttpClient.class);
    }

    @Test
    void applicationYml_defaultsToTwoRetries() throws Exception {
        String yml = new ClassPathResource("application.yml").getContentAsString(StandardCharsets.UTF_8);

        assertThat(yml).contains("max-network-retries: ${STRIPE_MAX_NETWORK_RETRIES:2}");
    }
}
