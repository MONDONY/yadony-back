package com.yadony.api.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Staging ne doit jamais renvoyer un navigateur sur la production.
 *
 * <p>Les URL de retour de l'abonnement PRO ont un défaut, dans {@code application.yml}, qui
 * pointe sur {@code https://yadony.com/pro} — c'est-à-dire la production. Tant que le profil
 * staging ne les surchargeait pas, un paiement de test y aurait renvoyé le navigateur sur le
 * portail réel, avec un {@code ?success=1} qu'aucun abonnement de production ne justifiait :
 * l'écran d'attente d'activation aurait tourné dans le vide, sans que rien ne signale l'erreur.
 *
 * <p>Le défaut n'est pas visible au démarrage — l'application se lance très bien avec une URL
 * de production. Il n'apparaît qu'au retour du premier Checkout, c'est-à-dire au pire moment.
 * D'où ce test, qui lit le fichier réellement embarqué plutôt que de faire confiance à une
 * relecture.
 */
@DisplayName("Configuration staging — aucune URL ne doit pointer sur la production")
class StagingBillingUrlsTest {

    private static final List<String> BILLING_URL_KEYS = List.of(
            "yadony.billing.success-url",
            "yadony.billing.cancel-url",
            "yadony.billing.portal-return-url");

    private PropertySource<?> stagingConfig() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
                "staging", new FileSystemResource("src/main/resources/application-staging.yml"));
        assertThat(sources).as("application-staging.yml doit être un YAML valide et non vide")
                .isNotEmpty();
        return sources.get(0);
    }

    @Test
    @DisplayName("les trois URL de retour de l'abonnement sont surchargées")
    void billingUrlsAreOverridden() throws IOException {
        PropertySource<?> config = stagingConfig();

        for (String key : BILLING_URL_KEYS) {
            assertThat(config.getProperty(key))
                    .as("%s doit être surchargée en staging, sinon elle hérite du défaut de "
                            + "application.yml qui pointe sur la production", key)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("aucune ne pointe sur le domaine de production")
    void billingUrlsNeverPointToProduction() throws IOException {
        PropertySource<?> config = stagingConfig();

        for (String key : BILLING_URL_KEYS) {
            String url = String.valueOf(config.getProperty(key));
            assertThat(url)
                    .as("%s renvoie sur la production", key)
                    .startsWith("https://staging.yadony.com/");
            // `yadony.com/` sans préfixe est le domaine de production ; `staging.yadony.com`
            // le contient comme suffixe, d'où la vérification sur le début de l'URL plutôt
            // qu'une recherche de sous-chaîne, qui serait vraie dans les deux cas.
        }
    }

    @Test
    @DisplayName("les URL de Stripe Connect restent elles aussi sur staging")
    void connectUrlsStayOnStaging() throws IOException {
        PropertySource<?> config = stagingConfig();

        assertThat(String.valueOf(config.getProperty("yadony.stripe.connect.return-url")))
                .startsWith("https://api-staging.yadony.com/");
        assertThat(String.valueOf(config.getProperty("yadony.stripe.connect.refresh-url")))
                .startsWith("https://api-staging.yadony.com/");
    }
}
