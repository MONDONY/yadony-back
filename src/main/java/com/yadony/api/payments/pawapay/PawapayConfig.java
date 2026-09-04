package com.yadony.api.payments.pawapay;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class PawapayConfig {

    private final PawapayProperties props;
    private final String activeProfiles;

    public PawapayConfig(PawapayProperties props,
                         @Value("${spring.profiles.active:}") String activeProfiles) {
        this.props = props;
        this.activeProfiles = activeProfiles == null ? "" : activeProfiles;
    }

    /**
     * En production, un rail activé sans vérification de signature accepterait n'importe
     * quel POST comme confirmation de paiement. Refus de démarrer plutôt qu'un warn.
     */
    @PostConstruct
    public void verifyProductionSafety() {
        boolean prod = activeProfiles.contains("prod");
        if (prod && props.enabled() && !props.callbackSignaturesRequired()) {
            throw new IllegalStateException(
                    "yadony.pawapay.enabled=true en prod exige PAWAPAY_CALLBACK_SIGNATURES=true");
        }
    }

    @Bean
    public RestClient pawapayRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000);
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiToken())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
