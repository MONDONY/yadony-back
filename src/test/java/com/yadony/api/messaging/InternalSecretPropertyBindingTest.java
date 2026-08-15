package com.yadony.api.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockPropertySource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garde-fou de câblage pour {@code yadony.internal.secret}.
 *
 * <p>Le secret n'était déclaré que dans {@code application-dev.yml}. En staging
 * et en prod, le placeholder de {@link MessagingNotifyController} retombait donc
 * sur sa valeur par défaut vide, et le contrôleur rejetait en 401 tous les
 * appels des Cloud Functions : aucune notification de nouveau message n'a jamais
 * été envoyée. Les tests unitaires du contrôleur ne l'ont pas vu parce qu'ils
 * posent le champ par réflexion, ce qui court-circuite la résolution de propriété.
 *
 * <p>Ce test vérifie donc la liaison elle-même, dans {@code application.yml}
 * (chargé par tous les profils), et non le comportement du contrôleur.
 */
class InternalSecretPropertyBindingTest {

    private static final String KEY = "yadony.internal.secret";
    private static final String ENV_VAR = "INTERNAL_SHARED_SECRET";

    @Test
    void internalSecret_isDeclaredInBaseConfiguration_soEveryProfileBindsIt() throws IOException {
        var environment = environmentWith(ENV_VAR, "secret-fourni-par-le-deploiement");

        assertThat(environment.resolvePlaceholders("${" + KEY + ":}"))
                .as("le secret partagé doit être lu depuis " + ENV_VAR
                        + " quel que soit le profil actif")
                .isEqualTo("secret-fourni-par-le-deploiement");
    }

    @Test
    void internalSecret_staysEmpty_whenEnvironmentVariableIsAbsent() throws IOException {
        var environment = environmentWith(null, null);

        assertThat(environment.resolvePlaceholders("${" + KEY + ":}"))
                .as("sans variable d'environnement, la valeur reste vide et le"
                        + " contrôleur refuse l'appel plutôt que d'accepter un secret par défaut")
                .isEmpty();
    }

    /** Charge {@code application.yml} seul, comme le ferait le démarrage sur n'importe quel profil. */
    private StandardEnvironment environmentWith(String variableName, String variableValue) throws IOException {
        var environment = new StandardEnvironment();

        if (variableName != null) {
            environment.getPropertySources()
                    .addFirst(new MockPropertySource().withProperty(variableName, variableValue));
        }

        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        sources.forEach(environment.getPropertySources()::addLast);

        return environment;
    }
}
