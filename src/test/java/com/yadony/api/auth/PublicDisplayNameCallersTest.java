package com.yadony.api.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garde-fou : aucun service ne doit reconstruire un nom d'affichage à la main.
 *
 * <p>Ce test existe parce qu'un premier passage avait manqué six helpers. Les symptômes
 * allaient du « Expéditeur » générique sur l'écran « À traiter » à la chaîne littérale
 * « null » dans la liste des comptes bloqués et « null null » dans le corps d'une
 * notification. Une méthode qui lit à la fois {@code getFirstName()} et {@code getLastName()}
 * duplique {@link UserEntity#publicDisplayName()} et finira par en diverger.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Nom d'affichage : source unique")
class PublicDisplayNameCallersTest {

    /**
     * Contextes où le nom complet est légitime et volontaire.
     *
     * <p>Back-office admin (modération, support), exports fiscaux DAC7 et export RGPD :
     * y abréger le patronyme dégraderait la donnée. {@code buildInitials} lit les deux
     * champs par nature. {@code buildPrefix} fabrique un code de parrainage, pas un nom.
     */
    private static final List<String> ALLOWED_FULL_NAME_CONTEXTS = List.of(
            "com/yadony/api/admin/",
            "com/yadony/api/export/",
            "com/yadony/api/payments/FiscalExportService",
            "com/yadony/api/referral/ReferralService",
            "com/yadony/api/common/MatchingTextUtil",
            "com/yadony/api/auth/UserEntity",
            "com/yadony/api/auth/AuthService"
    );

    @Test
    @DisplayName("aucun fichier hors back-office ne recompose un nom depuis prénom + nom")
    void noHandRolledDisplayNameOutsideAdminAndExports() throws Exception {
        List<String> offenders = new ArrayList<>();

        java.nio.file.Path root = java.nio.file.Path.of("src/main/java/com/yadony/api");
        try (var paths = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path p : paths.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = p.toString().replace('\\', '/');
                if (ALLOWED_FULL_NAME_CONTEXTS.stream().anyMatch(rel::contains)) {
                    continue;
                }
                String src = java.nio.file.Files.readString(p);
                if (src.contains("getFirstName()") && src.contains("getLastName()")) {
                    offenders.add(rel);
                }
            }
        }

        assertThat(offenders)
                .as("ces fichiers recomposent un nom au lieu d'appeler UserEntity.publicDisplayName()")
                .isEmpty();
    }

    /**
     * Le repli ne doit jamais être le rôle tenu dans l'échange : un même compte apparaissait
     * « Expéditeur » d'un côté et « Voyageur » de l'autre, et deux comptes sans prénom
     * devenaient indiscernables dans une même liste.
     */
    @ParameterizedTest
    @ValueSource(strings = {"Expéditeur", "Voyageur", "Un voyageur", "Un utilisateur"})
    @DisplayName("aucun repli d'affichage ne nomme le rôle")
    void noRoleBasedFallbackRemains(String forbidden) throws Exception {
        List<String> offenders = new ArrayList<>();

        java.nio.file.Path root = java.nio.file.Path.of("src/main/java/com/yadony/api");
        try (var paths = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path p : paths.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = p.toString().replace('\\', '/');
                // ParticipantDTO documente un champ « rôle » distinct du nom ; ConversationService
                // le renseigne. Ce sont des libellés de rôle assumés, pas des replis de nom.
                if (rel.contains("messaging/")) {
                    continue;
                }
                for (String line : java.nio.file.Files.readString(p).lines().toList()) {
                    String trimmed = line.stripLeading();
                    if (trimmed.startsWith("*") || trimmed.startsWith("//")) {
                        continue; // commentaire : ces mots y décrivent l'historique
                    }
                    if (line.contains('"' + forbidden + '"')) {
                        offenders.add(rel + " → " + line.strip());
                    }
                }
            }
        }

        assertThat(offenders).isEmpty();
    }

    /** publicDisplayName() doit rester public : c'est le point d'entrée que tout le reste appelle. */
    @Test
    @DisplayName("publicDisplayName() est accessible depuis tous les packages")
    void publicDisplayNameIsPublic() throws Exception {
        Method m = UserEntity.class.getMethod("publicDisplayName");
        assertThat(java.lang.reflect.Modifier.isPublic(m.getModifiers())).isTrue();
    }
}
