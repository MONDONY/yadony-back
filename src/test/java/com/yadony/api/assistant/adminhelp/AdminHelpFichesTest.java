package com.yadony.api.assistant.adminhelp;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Les vraies fiches du centre d'aide : elles se chargent, et les questions courantes trouvent la bonne fiche. */
class AdminHelpFichesTest {

    /** Seuil de application.yml (yadony.assistant.admin-help.min-score). */
    static final double SEUIL = 6.0;
    static AdminHelpIndex index;

    @BeforeAll
    static void load() {
        index = new AdminHelpIndex(AdminHelpLoader.load(new PathMatchingResourcePatternResolver(), AdminHelpIndex.ROOT));
    }

    @Test
    void chaqueRubriqueAUneFiche() {
        for (String category : AdminHelpLoader.CATEGORIES.keySet()) {
            assertThat(index.all()).as(category).anyMatch(a -> a.category().equals(category));
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "comment je fais pour regarder les colis en cours | voir-colis-en-cours",
            "colis en transit | voir-colis-en-cours",
            "annuler un colis | annuler-un-colis",
            "suspendre un utilisateur | suspendre-reactiver-compte",
            "rembourser un expéditeur | transactions-rembourser-expediteur",
            "changer mon mot de passe | changer-mot-de-passe",
            "répondre à un ticket support | support-repondre-ticket",
            "comment je crée un code promo | codes-promo",
            "mettre l'app en maintenance | mettre-en-maintenance",
            "valider une identité | verifications-identite",
            "exporter les transactions | exports",
            "ajouter un admin | administrateurs",
            "qui a supprimé ce compte | audit",
    })
    void lesQuestionsCourantesTrouventLaBonneFiche(String question, String expected) {
        List<AdminHelpIndex.Hit> hits = index.search(question, 3, SEUIL);
        assertThat(hits).extracting(h -> h.article().id()).contains(expected);
    }

    @ParameterizedTest
    @CsvSource({"prévisions météo du week-end", "quelle est la capitale du Japon", "écris moi un poème",
            "combien coûte un billet pour Paris"})
    void unHorsSujetNeRemonteRienAuSeuilDeLAssistant(String question) {
        assertThat(index.search(question, 4, SEUIL)).isEmpty();
    }
}
