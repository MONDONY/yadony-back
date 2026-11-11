package com.yadony.api.common.i18n;

import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garde-fou du chantier « textes serveur dans la langue de l'app » (défaut n° 9).
 *
 * <p>Les sources listées dans {@link #MIGRATED} ne doivent plus contenir de refus à texte
 * en dur : chaque erreur métier passe par {@code YadonyBusinessException.localized(..., clé)}
 * et la clé existe en français ET en anglais (titre et détail). La parité des deux fichiers de
 * messages est vérifiée par {@code MessagesBundleTest}. Quand un nouveau paquet est migré,
 * ajouter ses fichiers ici et cocher « migré » dans {@code docs/i18n-messages-serveur.md}.
 */
class ServerMessagesGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/yadony/api");

    /** Sources dont tous les refus métier sont localisés (paquet 1). */
    private static final List<String> MIGRATED = List.of(
            "matching/AnnouncementService.java",
            "matching/BidNegotiationService.java");

    private static final Pattern HARDCODED_BUSINESS = Pattern.compile("new\\s+YadonyBusinessException\\s*\\(");
    private static final Pattern FRENCH_IN_EXCEPTION = Pattern.compile(
            "new\\s+(?:Yadony\\w*|IllegalState|IllegalArgument|ResponseStatus)Exception\\s*\\(\\s*\"[^\"]*"
                    + "(?:[àâçéèêëîïôûùü]|\\b(?:Vous|Votre|introuvable|impossible)\\b)");
    private static final Pattern KEY = Pattern.compile("\"(problem\\.[a-z0-9.\\-]+)\"");

    private static String source(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative));
    }

    @Test
    @DisplayName("aucun refus métier à texte en dur dans les sources migrées")
    void noHardcodedBusinessExceptionInMigratedSources() throws IOException {
        for (String file : MIGRATED) {
            String src = source(file);
            assertThat(HARDCODED_BUSINESS.matcher(src).find())
                    .as("%s : utiliser YadonyBusinessException.localized(...) et une clé i18n", file)
                    .isFalse();
            assertThat(FRENCH_IN_EXCEPTION.matcher(src).find())
                    .as("%s : message français en dur dans une exception", file)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("chaque clé utilisée par les sources migrées existe en FR et en EN (titre et détail)")
    void everyKeyUsedByMigratedSourcesExistsInBothLanguages() throws IOException {
        Messages fr = TestMessages.fr();
        Messages en = TestMessages.en();
        int checked = 0;
        for (String file : MIGRATED) {
            Matcher m = KEY.matcher(source(file));
            while (m.find()) {
                String key = m.group(1);
                for (String suffix : List.of(".title", ".detail")) {
                    assertThat(fr.find(key + suffix)).as("%s%s absente de messages_fr (%s)", key, suffix, file).isPresent();
                    assertThat(en.find(key + suffix)).as("%s%s absente de messages_en (%s)", key, suffix, file).isPresent();
                }
                checked++;
            }
        }
        assertThat(checked).as("aucune clé repérée : le garde-fou ne vérifie plus rien").isGreaterThan(30);
    }

    @Test
    @DisplayName("localized : getMessage et getTitle restent en français, la langue demandée est résolue à part")
    void localizedExceptionKeepsFrenchMessageAndResolvesRequestedLanguage() {
        YadonyBusinessException e = YadonyBusinessException.localized(
                HttpStatus.FORBIDDEN, "draft-limit-reached", "problem.draft-limit-reached.upgrade", 3);

        assertThat(e.isLocalized()).isTrue();
        assertThat(e.getMessage()).isEqualTo("Limite de 3 brouillon(s) atteinte. Passez en PRO pour en créer davantage.");
        assertThat(e.getTitle()).isEqualTo("Draft Limit Reached");
        assertThat(e.getDetail(TestMessages.fr())).isEqualTo(e.getMessage());
        assertThat(e.getDetail(TestMessages.en())).isEqualTo("Limit of 3 draft(s) reached. Upgrade to PRO to create more.");
        assertThat(e.withProperties(java.util.Map.of("legIndex", 1)).getDetail(TestMessages.en()))
                .isEqualTo("Limit of 3 draft(s) reached. Upgrade to PRO to create more.");
    }

    @Test
    @DisplayName("exception historique à texte en dur : renvoyée telle quelle quelle que soit la langue")
    void legacyExceptionIsReturnedAsIs() {
        YadonyBusinessException e = new YadonyBusinessException(HttpStatus.CONFLICT, "x", "Titre", "Texte fixe");

        assertThat(e.isLocalized()).isFalse();
        assertThat(e.getDetail(TestMessages.en())).isEqualTo("Texte fixe");
        assertThat(e.getTitle(TestMessages.en())).isEqualTo("Titre");
        assertThat(Locale.ROOT).isNotNull();
    }
}
