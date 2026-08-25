package com.yadony.api.payments;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verrouille le schema des URL de retour Stripe Connect.
 *
 * <p>Regression du 2026-08-25 : la configuration de production portait
 * {@code return-url: "yadony://stripe/onboarding/complete"}. L'API AccountLink de
 * Stripe refuse les schemas applicatifs ({@code url_invalid}, « Not a valid URL »),
 * si bien que toute creation de lien d'onboarding echouait en 500. Le defaut ne
 * touchait que la production : {@code application-staging.yml} et
 * {@code application-dev.yml} portaient deja des URL HTTP(S).
 *
 * <p>Le deep link a sa place ailleurs, dans {@code deep-link-return} : c'est
 * {@link StripeOnboardingRedirectController} qui redirige vers lui, apres avoir
 * recu Stripe sur une URL HTTPS.
 */
class StripeConnectReturnUrlConfigTest {

    private static final Pattern URL_DE_RETOUR =
            Pattern.compile("^\\s*(return-url|refresh-url):\\s*(.+?)\\s*$", Pattern.MULTILINE);

    @DisplayName("les URL de retour Connect ne sont jamais des deep links")
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "application.yml",
            "application-staging.yml",
            "application-dev.yml",
    })
    void lesUrlDeRetourSontHttp(String fichier) throws IOException {
        Path chemin = Path.of("src/main/resources", fichier);
        if (!Files.exists(chemin)) {
            return;
        }
        String contenu = Files.readString(chemin);

        Matcher m = URL_DE_RETOUR.matcher(contenu);
        List<String> trouvees = m.results().map(r -> r.group(2)).toList();

        for (String brute : trouvees) {
            String valeur = brute.replace("\"", "").trim();
            // Forme ${VAR:defaut} : c'est le defaut qui part chez Stripe quand la
            // variable d'environnement n'est pas posee, donc c'est lui qu'on verifie.
            if (valeur.startsWith("${") && valeur.contains(":")) {
                valeur = valeur.substring(valeur.indexOf(':') + 1).replace("}", "").trim();
            }
            assertThat(valeur)
                    .as("%s : Stripe refuse tout schema autre que http(s) sur une AccountLink", fichier)
                    .startsWith("http");
        }
    }
}
