package com.yadony.api.kyc.provider.didit;

import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le client de production ne doit JAMAIS s'appuyer sur {@link SimpleClientHttpRequestFactory}.
 *
 * <p>Cette fabrique passe par {@code HttpURLConnection}, qui suit une redirection en
 * repostant SANS LE CORPS. Didit repondait alors
 * {@code 400 {"workflow_id":["This field is required."]}} — un message qui accuse un champ
 * pourtant correctement rempli, ce qui envoie chercher au mauvais endroit. Aucun test a
 * double (MockRestServiceServer) ne peut attraper ca : le probleme n'apparait qu'avec une
 * vraie pile HTTP et une vraie redirection.
 *
 * <p>Constate en staging le 2026-09-05 : le parcours KYC entier etait casse alors que cle,
 * workflow, URL de retour et serialisation JSON etaient tous exacts.
 */
class DiditClientRequestFactoryTest {

    @Test
    void leClientDeProductionNUtilisePasHttpUrlConnection() {
        DiditClient client = new DiditClient(
                new DiditProperties("https://verification.didit.me", "cle", "wf", "secret", "sandbox"));

        RestClient restClient = (RestClient) ReflectionTestUtils.getField(client, "restClient");
        ClientHttpRequestFactory factory =
                (ClientHttpRequestFactory) ReflectionTestUtils.getField(restClient, "clientRequestFactory");

        assertThat(factory)
                .as("SimpleClientHttpRequestFactory perd le corps d'un POST redirige")
                .isNotInstanceOf(SimpleClientHttpRequestFactory.class);
    }
}
