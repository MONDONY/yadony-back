package com.yadony.api.payments.pawapay;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Configuration
public class PawapayConfig {

    /** Seuls profils où un callback non signé est toléré : poste local et tests. */
    static final Set<String> UNSIGNED_CALLBACK_PROFILES = Set.of("dev", "test", "e2e");

    private final PawapayProperties props;
    private final Set<String> activeProfiles;

    @Autowired
    public PawapayConfig(PawapayProperties props, Environment environment) {
        this(props, String.join(",", environment.getActiveProfiles()));
    }

    PawapayConfig(PawapayProperties props, String activeProfiles) {
        this.props = props;
        this.activeProfiles = activeProfiles == null || activeProfiles.isBlank() ? Set.of()
                : Arrays.stream(activeProfiles.split(",")).map(String::trim).collect(Collectors.toSet());
    }

    /**
     * Un rail activé sans vérification de signature accepterait n'importe quel POST comme
     * confirmation de paiement, donc créditerait un solde sans dépôt réel. Refus de démarrer
     * plutôt qu'un warn, en prod comme en staging (joignable depuis Internet) : seuls le poste
     * local et les tests peuvent s'en passer.
     */
    @PostConstruct
    public void verifyProductionSafety() {
        boolean unsignedTolerated = activeProfiles.stream().anyMatch(UNSIGNED_CALLBACK_PROFILES::contains);
        if (!unsignedTolerated && props.enabled() && !props.callbackSignaturesRequired()) {
            throw new IllegalStateException(
                    "yadony.pawapay.enabled=true exige PAWAPAY_CALLBACK_SIGNATURES=true (profils actifs : "
                            + activeProfiles + ")");
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
