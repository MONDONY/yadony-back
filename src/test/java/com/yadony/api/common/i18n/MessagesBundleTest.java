package com.yadony.api.common.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Vérifie la cohérence des deux fichiers de messages (`messages_fr.properties`,
 * `messages_en.properties`). Réutilisé par toutes les tâches i18n : les règles
 * ci-dessous s'appliquent à l'ensemble des clés, pas seulement à celles ajoutées
 * dans cette tâche.
 */
class MessagesBundleTest {

    /**
     * Libellés français historiques contenant un tiret cadratin, exemptés de la
     * règle D10. Vide à ce stade (tâche A1) ; complété par A6.
     */
    private static final Set<String> EM_DASH_FR_HISTORIQUE = Set.of();

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)[,}]?");
    private static final Pattern LONE_APOSTROPHE = Pattern.compile("(?<!')'(?!')");

    private static Properties load(String resource) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = MessagesBundleTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("ressource " + resource + " introuvable sur le classpath").isNotNull();
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        }
        return properties;
    }

    private static Map<String, String> sorted(Properties properties) {
        Map<String, String> result = new LinkedHashMap<>();
        new TreeSet<>(properties.stringPropertyNames()).forEach(key -> result.put(key, properties.getProperty(key)));
        return result;
    }

    private static Set<String> placeholders(String value) {
        Set<String> result = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }

    @Test
    void memesClesDansLesDeuxFichiers() throws IOException {
        Map<String, String> fr = sorted(load("i18n/messages_fr.properties"));
        Map<String, String> en = sorted(load("i18n/messages_en.properties"));

        assertThat(fr.keySet()).isEqualTo(en.keySet());
    }

    @Test
    void aucuneValeurVide() throws IOException {
        for (String resource : new String[]{"i18n/messages_fr.properties", "i18n/messages_en.properties"}) {
            Map<String, String> messages = sorted(load(resource));
            messages.forEach((key, value) ->
                    assertThat(value).as(resource + " / " + key).isNotBlank());
        }
    }

    @Test
    void memesMarqueursParClef() throws IOException {
        Map<String, String> fr = sorted(load("i18n/messages_fr.properties"));
        Map<String, String> en = sorted(load("i18n/messages_en.properties"));

        fr.forEach((key, frValue) -> {
            String enValue = en.get(key);
            assertThat(enValue).as("clé manquante côté anglais : " + key).isNotNull();
            assertThat(placeholders(enValue)).as("marqueurs {n} différents pour " + key)
                    .isEqualTo(placeholders(frValue));
        });
    }

    @Test
    void aucuneApostropheSeule() throws IOException {
        for (String resource : new String[]{"i18n/messages_fr.properties", "i18n/messages_en.properties"}) {
            Map<String, String> messages = sorted(load(resource));
            messages.forEach((key, value) -> {
                if (LONE_APOSTROPHE.matcher(value).find()) {
                    fail(resource + " / " + key + " contient une apostrophe non doublée : " + value);
                }
            });
        }
    }

    @Test
    void chaqueValeurEstUnMessageFormatValide() throws IOException {
        Object[] fakeArgs = {"x0", "x1", "x2", "x3", "x4", "x5", "x6", "x7", "x8", "x9"};

        Map<String, String> fr = sorted(load("i18n/messages_fr.properties"));
        fr.forEach((key, value) -> {
            String unescaped = value.replace("''", "'");
            try {
                new MessageFormat(unescaped, Locale.FRENCH).format(fakeArgs);
            } catch (RuntimeException e) {
                fail("messages_fr.properties / " + key + " n'est pas un MessageFormat valide : " + e.getMessage());
            }
        });

        Map<String, String> en = sorted(load("i18n/messages_en.properties"));
        en.forEach((key, value) -> {
            String unescaped = value.replace("''", "'");
            try {
                new MessageFormat(unescaped, Locale.ENGLISH).format(fakeArgs);
            } catch (RuntimeException e) {
                fail("messages_en.properties / " + key + " n'est pas un MessageFormat valide : " + e.getMessage());
            }
        });
    }

    @Test
    void anglais_niTiretCadratinNiDonyHorsYadony() throws IOException {
        Map<String, String> en = sorted(load("i18n/messages_en.properties"));
        en.forEach((key, value) -> {
            assertThat(value).as("messages_en.properties / " + key + " contient un tiret cadratin").doesNotContain("—");
            String sansMarque = value.replace("Yadony", "");
            assertThat(sansMarque).as("messages_en.properties / " + key + " contient « Dony » hors « Yadony »")
                    .doesNotContain("Dony");
        });
    }

    @Test
    void francais_niDonyHorsYadony_etTiretCadratinSeulementEnHistorique() throws IOException {
        Map<String, String> fr = sorted(load("i18n/messages_fr.properties"));
        fr.forEach((key, value) -> {
            String sansMarque = value.replace("Yadony", "");
            assertThat(sansMarque).as("messages_fr.properties / " + key + " contient « Dony » hors « Yadony »")
                    .doesNotContain("Dony");

            if (value.contains("—")) {
                assertThat(EM_DASH_FR_HISTORIQUE)
                        .as("messages_fr.properties / " + key + " contient un tiret cadratin non répertorié")
                        .contains(key);
            }
        });
    }
}
