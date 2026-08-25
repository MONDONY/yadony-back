package com.yadony.api.payments;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verrouille le schema du deep link de retour Stripe Connect.
 *
 * <p>Regression du 2026-08-25 : le backend redirigeait vers
 * {@code yadony://stripe/onboarding/complete} alors que l'application
 * n'enregistrait et n'ecoutait que le schema {@code dony}. Le retour depuis le
 * navigateur ne revenait donc jamais dans l'application, sans aucun message.
 * L'application a ete alignee sur {@code yadony}, le nom du produit ; ce test
 * empeche le backend de repartir en sens inverse.
 *
 * <p>Le schema vit dans les manifestes mobiles ({@code CFBundleURLSchemes} cote iOS,
 * {@code android:scheme} cote Android) et dans le filtre de {@code app.dart}. Ces
 * quatre points doivent s'accorder ; ce test tient le seul que le backend controle.
 */
class StripeOnboardingRedirectControllerTest {

    /** Schema enregistre par l'application mobile. Toute divergence casse le retour. */
    private static final String SCHEMA_APPLICATION = "yadony://";

    private StripeOnboardingRedirectController controleurAvecDefauts() throws IOException {
        StripeOnboardingRedirectController c = new StripeOnboardingRedirectController();
        ReflectionTestUtils.setField(c, "deepLinkReturn", defautDeclare("deep-link-return"));
        ReflectionTestUtils.setField(c, "deepLinkRefresh", defautDeclare("deep-link-refresh"));
        return c;
    }

    /**
     * Lit le defaut ecrit dans l'annotation {@code @Value} du controleur, plutot que
     * de le recopier ici : recopier laisserait le test vert pendant que la valeur
     * reellement servie en production diverge.
     */
    private String defautDeclare(String cle) throws IOException {
        Path source = Path.of(
                "src/main/java/com/yadony/api/payments/StripeOnboardingRedirectController.java");
        Matcher m = Pattern.compile("\\$\\{yadony\\.stripe\\.connect\\." + cle + ":([^}]+)}")
                .matcher(Files.readString(source));
        assertThat(m.find()).as("defaut %s introuvable dans le controleur", cle).isTrue();
        return m.group(1);
    }

    @Test
    @DisplayName("le retour redirige vers le schema enregistre par l'application")
    void retourVersLeBonSchema() throws IOException {
        ResponseEntity<Void> reponse = controleurAvecDefauts().onboardingReturn();

        assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(reponse.getHeaders().getFirst(HttpHeaders.LOCATION))
                .as("l'application n'ecoute que %s : tout autre schema perd le retour",
                        SCHEMA_APPLICATION)
                .startsWith(SCHEMA_APPLICATION);
    }

    @Test
    @DisplayName("le rafraichissement redirige vers le schema enregistre par l'application")
    void rafraichissementVersLeBonSchema() throws IOException {
        ResponseEntity<Void> reponse = controleurAvecDefauts().onboardingRefresh();

        assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(reponse.getHeaders().getFirst(HttpHeaders.LOCATION))
                .startsWith(SCHEMA_APPLICATION);
    }
}
