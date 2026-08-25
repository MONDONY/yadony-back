package com.yadony.api.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Interdit l'ancien schema applicatif {@code dony://} dans tout {@code src/main}.
 *
 * <p>Regression du 2026-08-26 : le schema est passe a {@code yadony://} cote
 * application (manifestes iOS et Android, filtre de {@code app.dart}), mais
 * {@code PublicAnnouncementPageController} construisait encore
 * {@code dony://annonce/{id}}. Le bouton « Ouvrir dans l'application » des pages
 * de trajet partagees ne faisait donc plus rien, **sans aucune erreur** : un lien
 * vers un schema non enregistre echoue en silence.
 *
 * <p>Le schema est emis depuis plusieurs endroits sans lien entre eux — la page
 * publique d'annonce, la redirection Stripe Connect — et rien ne garantissait
 * leur accord. Ce test balaie l'ensemble des sources plutot que de verrouiller
 * un point a la fois : c'est la seule forme de garde qui couvre aussi les
 * endroits qui n'existent pas encore.
 */
class AppDeepLinkSchemeTest {

    /** Schema enregistre par l'application mobile (CFBundleURLSchemes / android:scheme). */
    private static final String SCHEMA = "yadony://";

    /** {@code dony://} non precede de {@code ya} : l'ancien schema, seul. */
    private static final Pattern ANCIEN_SCHEMA = Pattern.compile("(?<!ya)\\bdony://");

    @Test
    @DisplayName("aucune source ne porte l'ancien schéma dony://")
    void aucunAncienSchemaDansLesSources() throws IOException {
        List<String> fautifs = new ArrayList<>();

        try (Stream<Path> fichiers = Files.walk(Path.of("src/main"))) {
            fichiers.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".java") || n.endsWith(".html") || n.endsWith(".yml");
                    })
                    .forEach(p -> {
                        try {
                            String contenu = Files.readString(p);
                            if (ANCIEN_SCHEMA.matcher(contenu).find()) {
                                fautifs.add(p.toString());
                            }
                        } catch (IOException e) {
                            throw new IllegalStateException("lecture impossible : " + p, e);
                        }
                    });
        }

        assertThat(fautifs)
                .as("l'application n'enregistre que %s ; un lien vers dony:// échoue "
                        + "silencieusement, sans erreur visible", SCHEMA)
                .isEmpty();
    }
}
