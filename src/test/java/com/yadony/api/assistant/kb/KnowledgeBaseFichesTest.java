package com.yadony.api.assistant.kb;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeBaseFichesTest {

    /**
     * Le seuil de production, lu dans application.yml : le test ne dérive pas si on le règle. Mesures du
     * 16/10 sur les vraies fiches : questions complètes 16 à 30 ; mots seuls 8,7 à 13 ; hors-sujet
     * écartés au plus 6,1 ; hors-sujet qui passent encore 6,9 à 11,6 (ex. « meilleur téléphone » 10,1).
     */
    private static double productionMinScore() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        return Double.parseDouble(yaml.getObject().getProperty("yadony.assistant.min-score"));
    }

    private static KnowledgeBase productionKb() {
        KbCorpus corpus = KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), "classpath:assistant/kb");
        return new KnowledgeBase(corpus, productionMinScore());
    }

    private static String firstHit(KnowledgeBase kb, String locale, String question) {
        List<KbHit> hits = kb.search(locale, question, null, 4);
        assertThat(hits).as("fiches pour « %s »", question).isNotEmpty();
        return hits.get(0).article().id();
    }

    /**
     * L'écran d'accueil s'ouvre avec son explication : il ne doit pas porter le bouton « Mes alertes »,
     * que son texte ne mentionne pas. Le bouton vient de la fiche alertes-trajets, quand l'utilisateur pose la
     * question suggérée.
     */
    @Test
    void ecranAccueilNaPlusDeRoute_etLeBoutonAlertesVientDeAlertesTrajets() {
        KbCorpus corpus = KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), "classpath:assistant/kb");
        KnowledgeBase kb = productionKb();
        for (String locale : List.of("fr", "en")) {
            KbArticle accueil = corpus.articles(locale).stream()
                    .filter(a -> a.id().equals("ecran-accueil")).findFirst().orElseThrow();
            assertThat(accueil.routes()).as(locale).isEmpty();
            assertThat(accueil.screens()).as(locale).containsExactly("/home");
            KbArticle alertes = corpus.articles(locale).stream()
                    .filter(a -> a.id().equals("alertes-trajets")).findFirst().orElseThrow();
            assertThat(alertes.routes()).as(locale).extracting(KbRoute::path).containsExactly("/corridor-alerts");
            KbArticle activites = corpus.articles(locale).stream()
                    .filter(a -> a.id().equals("ecran-activites")).findFirst().orElseThrow();
            assertThat(activites.routes()).as(locale).extracting(KbRoute::path).contains("/corridor-alerts");
            assertThat(kb.screen(locale, "/home")).as(locale).map(KbArticle::id).contains("ecran-accueil");
        }
        assertThat(kb.routeWhitelist()).contains("/corridor-alerts");
        assertThat(firstHit(kb, "fr", "Comment être prévenu d'un nouveau trajet ?")).isEqualTo("alertes-trajets");
        assertThat(firstHit(kb, "en", "How do I get notified of a new trip?")).isEqualTo("alertes-trajets");
    }

    @Test
    void lesFichesDuDepotSontValides() {
        KbCorpus corpus = KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), "classpath:assistant/kb");
        assertThat(corpus.articles("fr")).hasSizeGreaterThanOrEqualTo(50);
        assertThat(corpus.articles("en")).hasSameSizeAs(corpus.articles("fr"));
    }

    @Test
    void lesQuestionsFrequentesTrouventUneFiche() {
        KnowledgeBase kb = productionKb();
        assertThat(kb.search("fr", "Comment envoyer un colis ?", null, 4)).isNotEmpty();
        assertThat(kb.search("fr", "Comment proposer un trajet ?", null, 4)).isNotEmpty();
        assertThat(kb.search("fr", "Quand le voyageur est-il payé ?", null, 4)).isNotEmpty();
        assertThat(kb.search("fr", "Comment vérifier mon identité ?", null, 4)).isNotEmpty();
        assertThat(kb.search("fr", "Où je change mon numéro ?", null, 4)).first()
                .extracting(h -> h.article().id()).isEqualTo("changer-numero");
        assertThat(kb.search("en", "How do I send a parcel?", null, 4)).isNotEmpty();
    }

    @Test
    void chaqueFicheFrExisteEnAnglaisAvecLesMemesRoutes() {
        KbCorpus corpus = KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), "classpath:assistant/kb");
        Map<String, List<String>> frRoutes = cheminsParId(corpus.articles("fr"));
        Map<String, List<String>> enRoutes = cheminsParId(corpus.articles("en"));
        assertThat(enRoutes.keySet()).isEqualTo(frRoutes.keySet());
        frRoutes.forEach((id, chemins) ->
                assertThat(enRoutes.get(id)).as("routes de la fiche %s", id).containsExactlyInAnyOrderElementsOf(chemins));
    }

    @Test
    void lesNouvellesFichesSontPresentesEnFrancaisEtEnAnglais() {
        KbCorpus corpus = KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), "classpath:assistant/kb");
        for (String locale : List.of("fr", "en")) {
            assertThat(corpus.articles(locale)).extracting(KbArticle::id)
                    .contains("securite-paiements", "donnees-personnelles", "douane", "villes-desservies");
        }
    }

    @Test
    void laFicheStatutVoyageurExisteEnFrancaisEtEnAnglaisAvecLaRouteGpProSansEcran() {
        KbCorpus corpus = KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), "classpath:assistant/kb");
        for (String locale : List.of("fr", "en")) {
            KbArticle fiche = corpus.articles(locale).stream()
                    .filter(x -> x.id().equals("statut-voyageur")).findFirst().orElseThrow();
            assertThat(fiche.routes()).as(locale).extracting(KbRoute::path).containsExactly("/profile/gp-pro");
            assertThat(fiche.screens()).as(locale).isEmpty();
            assertThat(fiche.body().length()).as(locale).isBetween(200, 2000);
            assertThat(fiche.body()).doesNotContain("\u2014");
        }
    }

    @Test
    void redevenirParticulier_listeBlancheContientGpPro_memeIdFrEn_etFicheLaPlusPertinente() {
        KbCorpus corpus = KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), "classpath:assistant/kb");
        assertThat(corpus.articles("en")).extracting(KbArticle::id)
                .containsExactlyInAnyOrderElementsOf(corpus.articles("fr").stream().map(KbArticle::id).toList());
        KnowledgeBase kb = productionKb();
        assertThat(kb.routeWhitelist()).contains("/profile/gp-pro");
        for (String question : List.of("Comment redevenir particulier ?", "Comment quitter GP Pro ?")) {
            List<KbHit> hits = kb.search("fr", question, null, 4);
            assertThat(hits).as(question).isNotEmpty();
            assertThat(hits).as(question).anySatisfy(h -> assertThat(h.article().id()).isEqualTo("statut-voyageur"));
            assertThat(hits.get(0).article().routes()).as(question).extracting(KbRoute::path).contains("/profile/gp-pro");
        }
        List<KbHit> en = kb.search("en", "How do I become an individual again?", null, 4);
        assertThat(en).isNotEmpty();
        assertThat(en.get(0).article().routes()).extracting(KbRoute::path).contains("/profile/gp-pro");
    }

    @Test
    void laLimiteMensuelle_precise_queLesBrouillonsRestentEnregistrables() {
        KbCorpus corpus = KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), "classpath:assistant/kb");
        for (String id : List.of("statut-voyageur")) {
            String fr = corpus.articles("fr").stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow().body();
            String en = corpus.articles("en").stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow().body();
            assertThat(fr).as("fr/" + id).contains("brouillon").contains("Publier").doesNotContain("\u2014");
            assertThat(en).as("en/" + id).containsIgnoringCase("draft").contains("Publish").doesNotContain("\u2014");
        }
    }

    @Test
    void lesFichesTouchees_parLeStatutVoyageur_restentSousLeursLimites() {
        KbCorpus corpus = KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), "classpath:assistant/kb");
        for (String locale : List.of("fr", "en")) {
            for (String id : List.of("gp-pro", "proposer-trajet", "ecran-modifier-profil")) {
                KbArticle fiche = corpus.articles(locale).stream()
                        .filter(x -> x.id().equals(id)).findFirst().orElseThrow();
                assertThat(fiche.body().length()).as(locale + "/" + id)
                        .isLessThanOrEqualTo(fiche.screens().isEmpty() ? 2000 : 600);
                assertThat(fiche.body().toLowerCase()).as(locale + "/" + id)
                        .containsAnyOf("particulier", "individual");
            }
        }
    }

    @Test
    void laFicheStatutVoyageurExpliqueGpProLaLimiteMensuelleEtLaSortieEnUnClic() {
        KbCorpus corpus = KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), "classpath:assistant/kb");
        String fr = corpus.articles("fr").stream().filter(x -> x.id().equals("statut-voyageur"))
                .findFirst().orElseThrow().body();
        assertThat(fr).contains("GP Pro", "limite", "Redevenir particulier", "Plus tard", "validé par l'équipe");
        String en = corpus.articles("en").stream().filter(x -> x.id().equals("statut-voyageur"))
                .findFirst().orElseThrow().body();
        assertThat(en).contains("GP Pro", "limit", "Become an individual again", "Later", "validated by the Yadony team");
        // L'ancienne promesse (déclaration libre de professionnel, interrupteur obligatoire) a disparu.
        assertThat(fr).doesNotContain("Yadony peut rendre cette déclaration obligatoire");
        assertThat(en).doesNotContain("can make this declaration mandatory");
    }

    @Test
    void laFicheStatutVoyageurExpliqueLaQuestionConfirmezVotreStatut_sansBlocage() {
        KbCorpus corpus = KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), "classpath:assistant/kb");
        KbArticle fr = corpus.articles("fr").stream().filter(x -> x.id().equals("statut-voyageur"))
                .findFirst().orElseThrow();
        KbArticle en = corpus.articles("en").stream().filter(x -> x.id().equals("statut-voyageur"))
                .findFirst().orElseThrow();
        assertThat(fr.body()).contains("Confirmez votre statut", "Rien n'est bloqué", "plus de 4 trajets dans le mois civil");
        assertThat(fr.body()).doesNotContain("souvent avec des colis");
        assertThat(en.body()).contains("Confirm your status", "Nothing is blocked", "more than 4 trips in a calendar month");
        assertThat(en.body()).doesNotContain("often travel with parcels");
        assertThat(fr.body().length()).isLessThanOrEqualTo(2000);
        assertThat(en.body().length()).isLessThanOrEqualTo(2000);
    }

    @Test
    void laFicheInformationsFiscales_memeIdFrEn_routeTaxInfo_jamaisBloquante() {
        KbCorpus corpus = KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), "classpath:assistant/kb");
        for (String locale : List.of("fr", "en")) {
            KbArticle fiche = corpus.articles(locale).stream()
                    .filter(x -> x.id().equals("informations-fiscales")).findFirst().orElseThrow();
            assertThat(fiche.routes()).as(locale).extracting(KbRoute::path).containsExactly("/profile/tax-info");
            assertThat(fiche.body().length()).as(locale).isBetween(200, 2000);
            assertThat(fiche.body()).doesNotContain("\u2014").contains("DAC7");
        }
        String fr = corpus.articles("fr").stream().filter(x -> x.id().equals("informations-fiscales"))
                .findFirst().orElseThrow().body();
        String en = corpus.articles("en").stream().filter(x -> x.id().equals("informations-fiscales"))
                .findFirst().orElseThrow().body();
        // Prudence : l'écran n'existe qu'avec le lot 3d de l'app, et rien n'est jamais obligatoire pour agir.
        assertThat(fr).contains("Si cette fonction est disponible", "Rien n'est bloqué", "deux rappels");
        assertThat(en).contains("If this feature is available", "Nothing is blocked", "two reminders");
        // Collecte ciblée (11/11/2026) : plus de condition « déjà payé », seul le pays si inconnu, seulement ce qui
        // manque, NIF français à 13 chiffres, lieu de naissance seulement sans NIF.
        assertThat(fr).doesNotContain("déjà été payé").doesNotContain("jamais redemandés")
                .contains("seul ce pays", "seulement ce qui manque", "13 chiffres", "lieu de naissance",
                        "Ce n'est plus mon adresse");
        assertThat(en).doesNotContain("already been paid").doesNotContain("never asked again")
                .contains("only that country", "only what is missing", "13 digits", "place of birth",
                        "This is no longer my address");
    }

    @Test
    void laFicheGpProNeSeContredisPasSurLaLimiteDeTrajets() {
        KbCorpus corpus = KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), "classpath:assistant/kb");
        String fr = corpus.articles("fr").stream().filter(x -> x.id().equals("gp-pro")).findFirst().orElseThrow().body();
        String en = corpus.articles("en").stream().filter(x -> x.id().equals("gp-pro")).findFirst().orElseThrow().body();
        assertThat(fr).doesNotContain("annonces en plus").contains("n'a pas de limite de trajets par mois");
        assertThat(en).doesNotContain("extra listings").contains("no monthly trip limit");
        assertThat(fr.length()).isLessThanOrEqualTo(2000);
        assertThat(en.length()).isLessThanOrEqualTo(2000);
    }

    @Test
    void lesQuestionsSurLaLimiteEtLaSortieGpProTrouventLaFicheStatutVoyageur() {
        KnowledgeBase kb = productionKb();
        assertThat(firstHit(kb, "fr", "Comment redevenir particulier ?")).isEqualTo("statut-voyageur");
        assertThat(firstHit(kb, "fr", "Pourquoi j'ai une limite de trajets par mois ?")).isEqualTo("statut-voyageur");
        assertThat(firstHit(kb, "en", "How do I become an individual again?")).isEqualTo("statut-voyageur");
        assertThat(firstHit(kb, "en", "Why is there a monthly trip limit?")).isEqualTo("statut-voyageur");
    }

    @Test
    void laQuestionSurLeStatutTrouveLaFicheStatutVoyageur() {
        KnowledgeBase kb = productionKb();
        assertThat(firstHit(kb, "fr", "Que change le statut particulier pour l'expéditeur ?"))
                .isEqualTo("statut-voyageur");
        assertThat(firstHit(kb, "fr", "Voyager en tant que particulier ou professionnel ?"))
                .isEqualTo("statut-voyageur");
        assertThat(firstHit(kb, "en", "Traveling as an individual or as a professional?"))
                .isEqualTo("statut-voyageur");
    }

    @Test
    void lesQuestionsReellesTrouventLeurFicheEnTete() {
        KnowledgeBase kb = productionKb();
        Map<String, String> fr = new LinkedHashMap<>();
        fr.put("Comment changer mon numéro ?", "changer-numero");
        fr.put("Quand le voyageur est-il payé ?", "quand-paye-voyageur");
        fr.put("Comment annuler mon trajet ?", "annuler-trajet");
        fr.put("Comment vérifier mon identité ?", "verifier-identite");
        fr.put("Comment ouvrir un litige ?", "ouvrir-litige");
        fr.put("Comment supprimer mon compte ?", "supprimer-compte");
        fr.put("Le destinataire est absent, que faire ?", "destinataire-absent");
        fr.put("Comment recharger mon solde ?", "recharger-solde");
        fr.put("Quelle est la commission de Yadony ?", "commission");
        fr.put("Comment scanner le QR code du colis ?", "scanner-qr");
        fr.forEach((question, id) -> assertThat(firstHit(kb, "fr", question)).as(question).isEqualTo(id));

        Map<String, String> en = new LinkedHashMap<>();
        en.put("How do I change my phone number?", "changer-numero");
        en.put("When is the traveler paid?", "quand-paye-voyageur");
        en.put("How do I cancel my trip?", "annuler-trajet");
        en.put("How do I verify my identity?", "verifier-identite");
        en.forEach((question, id) -> assertThat(firstHit(kb, "en", question)).as(question).isEqualTo(id));
    }

    @Test
    void lesQuestionsDesNouvellesFichesTrouventLaBonneFiche() {
        KnowledgeBase kb = productionKb();
        assertThat(firstHit(kb, "fr", "Quelles villes sont desservies ?")).isEqualTo("villes-desservies");
        assertThat(firstHit(kb, "fr", "Faut-il déclarer le colis à la douane ?")).isEqualTo("douane");
        assertThat(firstHit(kb, "fr", "Mes données sont-elles revendues ?")).isEqualTo("donnees-personnelles");
        assertThat(firstHit(kb, "fr", "Le paiement par carte est-il sécurisé ?")).isEqualTo("securite-paiements");
        assertThat(firstHit(kb, "en", "Which cities are served?")).isEqualTo("villes-desservies");
        assertThat(firstHit(kb, "en", "Do I need to declare the parcel to customs?")).isEqualTo("douane");
        assertThat(firstHit(kb, "en", "Is my data sold?")).isEqualTo("donnees-personnelles");
        assertThat(firstHit(kb, "en", "Is card payment secure?")).isEqualTo("securite-paiements");
    }

    /** Une question proposée sous l'explication d'un écran ne doit jamais aboutir à « je n'ai pas trouvé ». */
    @Test
    void chaqueQuestionDEcranTrouveUneFiche() {
        KnowledgeBase kb = productionKb();
        KbCorpus corpus = KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), "classpath:assistant/kb");
        int screens = 0;
        java.util.List<String> unanswered = new java.util.ArrayList<>();
        for (String locale : KnowledgeBaseLoader.LOCALES) {
            for (KbArticle a : corpus.articles(locale)) {
                if (a.screens().isEmpty()) continue;
                screens++;
                assertThat(a.questions()).as(locale + "/" + a.id()).isNotEmpty();
                for (String q : a.questions()) {
                    if (kb.search(locale, q, null, 4).isEmpty()) unanswered.add(locale + "/" + a.id() + " : " + q);
                }
                for (String screen : a.screens()) {
                    assertThat(kb.screen(locale, screen)).as(screen).contains(a);
                }
            }
        }
        assertThat(unanswered).isEmpty();
        assertThat(screens).isGreaterThanOrEqualTo(80);
    }

    @Test
    void laVideoDAccueilTrouveSaFicheSansPrendreCelleDesTutoriels() {
        KnowledgeBase kb = productionKb();
        assertThat(firstHit(kb, "fr", "Pourquoi une vidéo de présentation s'affiche à l'ouverture ?")).isEqualTo("video-accueil");
        assertThat(firstHit(kb, "fr", "Comment revoir la vidéo d'accueil ?")).isEqualTo("video-accueil");
        assertThat(firstHit(kb, "en", "Why does a welcome video pop up?")).isEqualTo("video-accueil");
        assertThat(firstHit(kb, "fr", "Où sont les tutoriels ?")).isEqualTo("tutoriels-videos");
        assertThat(firstHit(kb, "en", "Where are the tutorials?")).isEqualTo("tutoriels-videos");
        KbCorpus corpus = KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), "classpath:assistant/kb");
        for (String locale : List.of("fr", "en")) {
            KbArticle fiche = corpus.articles(locale).stream().filter(a -> a.id().equals("video-accueil"))
                    .findFirst().orElseThrow();
            // Route existante de l'app (router.dart), déjà autorisée par la fiche des tutoriels.
            assertThat(fiche.routes()).extracting(KbRoute::path).containsExactly("/profile/community");
        }
    }

    @Test
    void leColisNonConformeMeneAuRefusDuVoyageur() {
        KnowledgeBase kb = productionKb();
        assertThat(firstHit(kb, "fr", "Le colis ne correspond pas à ce qui a été déclaré, que faire ?"))
                .isEqualTo("colis-non-conforme");
        assertThat(firstHit(kb, "fr", "Comment corriger le prix d'un colis mal déclaré ?"))
                .isEqualTo("colis-non-conforme");
        assertThat(firstHit(kb, "en", "The parcel doesn't match what was declared, what can I do?"))
                .isEqualTo("colis-non-conforme");
    }

    @Test
    void leSupplementDePrixTrouveSaFicheDansLesDeuxLangues() {
        KnowledgeBase kb = productionKb();
        assertThat(firstHit(kb, "fr", "Comment demander un supplément à l'expéditeur ?")).isEqualTo("supplement-prix");
        assertThat(firstHit(kb, "fr", "Le voyageur me demande de payer plus, que faire ?")).isEqualTo("supplement-prix");
        assertThat(firstHit(kb, "fr", "supplément")).isEqualTo("supplement-prix");
        assertThat(firstHit(kb, "en", "How do I request an extra charge?")).isEqualTo("supplement-prix");
        assertThat(firstHit(kb, "en", "The traveler asks me to pay more, what can I do?")).isEqualTo("supplement-prix");
        KbCorpus corpus = KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), "classpath:assistant/kb");
        for (String locale : List.of("fr", "en")) {
            KbArticle fiche = corpus.articles(locale).stream().filter(a -> a.id().equals("supplement-prix"))
                    .findFirst().orElseThrow();
            // Règles réelles du code (BidSupplementProperties) : bornes, plafond, délai, marge avant départ.
            assertThat(fiche.body()).as(locale).contains("300").contains("2").contains("15").contains("30");
            assertThat(fiche.routes()).extracting(KbRoute::path).containsExactly("/demandes", "/envois", "/tracking");
        }
    }

    @Test
    void modifierUnColisAccepteTrouveSaFicheDansLesDeuxLangues() {
        KnowledgeBase kb = productionKb();
        assertThat(firstHit(kb, "fr", "Comment ajouter des kilos à mon colis déjà accepté ?")).isEqualTo("modifier-colis");
        assertThat(firstHit(kb, "fr", "Puis-je agrandir mon colis ?")).isEqualTo("modifier-colis");
        assertThat(firstHit(kb, "fr", "modifier mon colis")).isEqualTo("modifier-colis");
        assertThat(firstHit(kb, "en", "How do I add kilos to my accepted parcel?")).isEqualTo("modifier-colis");
        assertThat(firstHit(kb, "en", "Can I change my parcel after it was accepted?")).isEqualTo("modifier-colis");
        KbCorpus corpus = KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), "classpath:assistant/kb");
        Map<String, List<String>> phrases = Map.of(
                "fr", List.of("48 h", "2 modifications acceptées au plus", "2 h 15 avant le départ",
                        "le voyageur règle la commission Yadony de la modification",
                        "Solde du voyageur insuffisant : acceptation refusée, rien n'est débité, la demande reste ouverte",
                        "carte du voyageur refusée : rien n'est débité, retour en attente de réponse",
                        "Le voyageur peut réessayer jusqu'à l'échéance", "Commission rendue si le colis n'est pas livré", "Les kilos en plus ne sont pas là",
                        "Hors espèces, le colis et sa modification sont versés ensemble",
                        "remboursés ensemble", "demandez au voyageur d'ajuster le prix"),
                "en", List.of("48 hours", "at most 2 accepted changes", "2 h 15 before departure",
                        "the traveler pays the Yadony service fee of the change",
                        "Traveler's balance too low: acceptance refused, nothing is charged, the request stays open",
                        "traveler's card declined: nothing is charged, back to awaiting an answer",
                        "The traveler can retry until the deadline", "Fee returned if the parcel isn't delivered", "The extra kilos are not there",
                        "Outside cash, the parcel and its change are paid out together",
                        "refunded together", "ask the traveler to adjust the price"));
        for (String locale : List.of("fr", "en")) {
            KbArticle fiche = corpus.articles(locale).stream().filter(a -> a.id().equals("modifier-colis"))
                    .findFirst().orElseThrow();
            // Règles réelles du code (BidAmendmentProperties, BidAmendmentCashRail, BidAmendmentWindow).
            assertThat(fiche.body()).as(locale).contains(phrases.get(locale));
            // Route statique de l'app, déjà autorisée par d'autres fiches (pas de /bids nu).
            assertThat(fiche.routes()).extracting(KbRoute::path).containsExactly("/envois");
        }
    }

    @Test
    void lesFichesDePaiementEtDeRemiseParlentDeLaModificationDuColis() {
        KbCorpus corpus = KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), "classpath:assistant/kb");
        // Une phrase propre au complément ajouté dans chaque fiche (id -> [français, anglais]).
        Map<String, List<String>> complements = Map.of(
                "remboursement", List.of("les deux transactions sont rendues ensemble si le colis n'est pas livré",
                        "both transactions are returned together if the parcel isn't delivered"),
                "paiement-especes", List.of(
                        "Les frais de service de la modification sont prélevés au voyageur dès qu'il l'accepte",
                        "The change's service fee is taken from the traveler as soon as they accept it"),
                "quand-paye-voyageur", List.of("est versée en même temps que le colis, après la livraison",
                        "is paid out together with the parcel, after delivery"),
                "remise-colis", List.of("Les kilos en plus ne sont pas là", "The extra kilos are not there"));
        complements.forEach((id, phrases) -> {
            for (String locale : List.of("fr", "en")) {
                KbArticle fiche = corpus.articles(locale).stream().filter(a -> a.id().equals(id))
                        .findFirst().orElseThrow();
                assertThat(fiche.body()).as(locale + "/" + id).contains(phrases.get(locale.equals("fr") ? 0 : 1));
            }
        });
    }

    @Test
    void leNumeroDejaUtiliseMeneAChangerNumero() {
        // YADONY-FLUTTER-PROD-Y : le 409 « numéro déjà utilisé » doit trouver sa réponse.
        KnowledgeBase kb = productionKb();
        assertThat(firstHit(kb, "fr", "Ce numéro est déjà utilisé par un autre compte"))
                .isEqualTo("changer-numero");
        assertThat(firstHit(kb, "en", "This number is already used by another account"))
                .isEqualTo("changer-numero");
    }

    @Test
    void lesMotsSeulsUsuelsPassentLeSeuil() {
        KnowledgeBase kb = productionKb();
        assertThat(firstHit(kb, "fr", "remboursement")).isEqualTo("remboursement");
        assertThat(firstHit(kb, "fr", "litige")).isEqualTo("ouvrir-litige");
        assertThat(firstHit(kb, "fr", "numéro")).isEqualTo("changer-numero");
        assertThat(firstHit(kb, "fr", "paiement")).isEqualTo("securite-paiements");
        assertThat(firstHit(kb, "en", "dispute")).isEqualTo("ouvrir-litige");
    }

    @Test
    void lesQuestionsHorsSujetNeRamenentAucuneFiche() {
        KnowledgeBase kb = productionKb();
        for (String question : List.of(
                "Quelle est la météo à Dakar demain ?",
                "Recette du thiéboudienne",
                "Qui a gagné la CAN ?",
                "Comment réparer ma voiture ?",
                "Qui est le président de la France ?",
                "Raconte-moi une blague")) {
            assertThat(kb.search("fr", question, null, 4)).as(question).isEmpty();
        }
        for (String question : List.of(
                "What is the weather in Dakar tomorrow?",
                "Who won the world cup?",
                "What is the capital of Australia?",
                "Who is the president of France?")) {
            assertThat(kb.search("en", question, null, 4)).as(question).isEmpty();
        }
    }

    @Test
    void uneRelanceEnChaineNeTrouveRienAvecLaSeuleQuestionPrecedente() {
        // FLUTTER-QV : « comment je fais ? » après « et après ? » ne passe pas le seuil, même avec la question
        // précédente à demi-poids ; la question d'avant (« Comment recharger mon solde ? ») ramène la fiche.
        // AssistantService relance alors avec les deux dernières questions utilisateur.
        KnowledgeBase kb = productionKb();
        assertThat(kb.search("fr", "comment je fais ?", "et après ?", 4)).isEmpty();
        assertThat(kb.search("fr", "comment je fais ?\net après ?\nComment recharger mon solde ?", null, 4))
                .first().extracting(h -> h.article().id()).isEqualTo("recharger-solde");
    }

    @Test
    void laDeuxiemeFicheDeLaDouaneNeVoleJamaisLaPremierePlace() {
        // « déclarer » est aussi dans « Valeur déclarée » : la fiche douane doit rester devant.
        List<String> ids = productionKb().search("fr", "Faut-il déclarer le colis à la douane ?", null, 4)
                .stream().map(h -> h.article().id()).toList();
        assertThat(ids).startsWith("douane");
    }

    @Test
    void livraisonConfirmeeCiteLeTitreReelDeLaFicheDestinataireAbsent() {
        KbCorpus corpus = KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), "classpath:assistant/kb");
        for (String locale : List.of("fr", "en")) {
            String title = corpus.articles(locale).stream().filter(a -> a.id().equals("destinataire-absent"))
                    .findFirst().orElseThrow().title();
            KbArticle livraison = corpus.articles(locale).stream().filter(a -> a.id().equals("livraison-confirmee"))
                    .findFirst().orElseThrow();
            assertThat(livraison.body()).as(locale).contains(title);
        }
    }

    @Test
    void moyensDePaiementNAnnoncentPasUnNombreQuIlsContredisent() {
        KbCorpus corpus = KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), "classpath:assistant/kb");
        for (String locale : List.of("fr", "en")) {
            KbArticle moyens = corpus.articles(locale).stream().filter(a -> a.id().equals("moyens-paiement"))
                    .findFirst().orElseThrow();
            assertThat(moyens.body()).as(locale).doesNotContainIgnoringCase("trois moyens")
                    .doesNotContainIgnoringCase("three methods");
        }
    }

    @Test
    void kycDelaiNeSeCaleSurLeMotMinutes() {
        KbCorpus corpus = KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), "classpath:assistant/kb");
        for (String locale : List.of("fr", "en")) {
            KbArticle delai = corpus.articles(locale).stream().filter(a -> a.id().equals("kyc-delai"))
                    .findFirst().orElseThrow();
            assertThat(delai.keywords()).as(locale).doesNotContain("minutes");
        }
    }

    private static Map<String, List<String>> cheminsParId(List<KbArticle> articles) {
        return articles.stream().collect(Collectors.toMap(KbArticle::id,
                a -> a.routes().stream().map(KbRoute::path).toList(), (x, y) -> x));
    }

    @Test
    void lesFichesDecouverteSontDesFonctionnalitesReellesDesDeuxLangues() {
        KnowledgeBase kb = productionKb();
        KbCorpus corpus = KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), "classpath:assistant/kb");
        java.util.Set<String> enIds = corpus.articles("en").stream().map(KbArticle::id)
                .collect(Collectors.toSet());
        java.util.Set<String> frIds = corpus.articles("fr").stream().map(KbArticle::id)
                .collect(Collectors.toSet());
        // Chaque exclusion désigne une fiche qui existe : une faute de frappe la laisserait passer.
        assertThat(frIds).containsAll(KnowledgeBase.NOT_DISCOVERABLE);
        assertThat(enIds).containsAll(KnowledgeBase.NOT_DISCOVERABLE);

        List<KbArticle> picked = kb.discoveryCandidates("fr", java.util.Set.of("parrainage"), "Comment recharger ?", 5);
        assertThat(picked).hasSize(5).allSatisfy(a -> {
            assertThat(a.screens()).isEmpty();
            assertThat(KnowledgeBase.NOT_DISCOVERABLE).doesNotContain(a.id());
            assertThat(a.id()).isNotEqualTo("parrainage");
            assertThat(enIds).contains(a.id());
        });
        assertThat(picked).doesNotHaveDuplicates();
        // Même question, même liste ; autre question, autre point de départ.
        assertThat(kb.discoveryCandidates("fr", java.util.Set.of("parrainage"), "Comment recharger ?", 5))
                .isEqualTo(picked);
        assertThat(kb.discoveryCandidates("en", java.util.Set.of(), "How do I top up?", 5))
                .allSatisfy(a -> assertThat(a.locale()).isEqualTo("en"));
        assertThat(kb.discoveryCandidates("fr", java.util.Set.of(), "q", 0)).isEmpty();
        assertThat(kb.discoveryCandidates("de", java.util.Set.of(), null, 1))
                .allSatisfy(a -> assertThat(a.locale()).isEqualTo("fr"));
    }

    private static java.util.Optional<KbArticle> article(String locale, String id) {
        KbCorpus corpus = KnowledgeBaseLoader.load(new PathMatchingResourcePatternResolver(), "classpath:assistant/kb");
        return corpus.articles(locale).stream().filter(a -> a.id().equals(id)).findFirst();
    }

    private static String body(String locale, String id) {
        return article(locale, id).orElseThrow(() -> new AssertionError(locale + "/" + id)).body();
    }

    @Test
    void reporterTrajet_listsModeAwareReasons() {
        assertThat(body("fr", "reporter-trajet")).contains("Panne ou contretemps").contains("Vol annulé")
                .contains("Transport annulé").contains("Voyage repoussé").contains("Autre raison");
        assertThat(body("en", "reporter-trajet")).contains("Breakdown or setback").contains("Flight canceled")
                .contains("Transport canceled").contains("Trip postponed").contains("Other reason");
        assertThat(article("fr", "reporter-trajet").orElseThrow().keywords()).contains("panne", "transport annulé");
    }

    @Test
    void annulerTrajet_firstReasonFollowsTheMode() {
        assertThat(body("fr", "annuler-trajet")).contains("vol annulé").contains("panne ou contretemps")
                .contains("transport annulé");
        assertThat(body("en", "annuler-trajet")).contains("flight canceled").contains("breakdown or setback")
                .contains("transport canceled");
    }

    @Test
    void ecranCreerTrajet_describesFiveSteps() {
        assertThat(body("fr", "ecran-creer-trajet")).contains("« Mode »").contains("« Trajet »")
                .contains("« Capacité »").contains("« Prix »").contains("« Conditions »").contains("Passer");
        assertThat(body("en", "ecran-creer-trajet")).contains("\"Mode\"").contains("\"Trip\"")
                .contains("\"Capacity\"").contains("\"Price\"").contains("\"Terms\"").contains("Skip");
    }

    @Test
    void proposerTrajet_describesTheModeStep() {
        assertThat(body("fr", "proposer-trajet")).contains("« Mode »").contains("« Conditions »");
        assertThat(body("en", "proposer-trajet")).contains("\"Mode\"").contains("\"Terms\"");
    }

    @Test
    void modesTransport_existsInBothLanguages() {
        assertThat(article("fr", "modes-transport")).isPresent();
        assertThat(article("en", "modes-transport")).isPresent();
        assertThat(body("fr", "modes-transport")).contains("3 jours").contains("14 jours").contains("120 jours");
        assertThat(body("en", "modes-transport")).contains("3 days").contains("14 days").contains("120 days");
        assertThat(article("fr", "modes-transport").orElseThrow().keywords())
                .contains("bateau", "route", "voiture", "car", "camion", "conteneur", "mode de transport");
    }

    @Test
    void envoyerColis_noLongerSaysLuggage() {
        assertThat(body("fr", "envoyer-colis")).doesNotContain("bagages");
        assertThat(body("en", "envoyer-colis")).doesNotContain("luggage");
    }

    @Test
    void ecranNouvelleDemande_describesTheTransportModeAndTheAiIcon() {
        assertThat(body("fr", "ecran-nouvelle-demande")).contains("Mode de transport").contains("Peu importe")
                .contains("Créer avec l'IA");
        assertThat(body("en", "ecran-nouvelle-demande")).contains("Transport mode").contains("Any")
                .contains("Create with AI");
    }

    @Test
    void rechercherTrajet_describesTheTransportChipAndSection() {
        assertThat(body("fr", "rechercher-trajet")).contains("« Transport »");
        assertThat(body("en", "rechercher-trajet")).contains("\"Transport\"");
        assertThat(body("fr", "ecran-composer-recherche")).contains("TRANSPORT").contains("Tous");
        assertThat(body("en", "ecran-composer-recherche")).contains("TRANSPORT").contains("All");
    }

    @Test
    void lesFichesModesDeTransport_gardentLesLimites_etSansTiretCadratin() {
        List<String> ids = List.of("reporter-trajet", "annuler-trajet", "proposer-trajet", "ecran-creer-trajet",
                "envoyer-colis", "ecran-nouvelle-demande", "rechercher-trajet", "ecran-composer-recherche",
                "modes-transport");
        for (String locale : List.of("fr", "en")) {
            for (String id : ids) {
                KbArticle fiche = article(locale, id).orElseThrow();
                assertThat(fiche.body().length()).as(locale + "/" + id)
                        .isLessThanOrEqualTo(fiche.screens().isEmpty() ? 2000 : 600);
                assertThat(fiche.body()).as(locale + "/" + id).doesNotContain("\u2014");
            }
        }
    }

    @Test
    void laQuestionSurLesModesDeTransportTrouveSaFiche() {
        KnowledgeBase kb = productionKb();
        assertThat(firstHit(kb, "fr", "Quels modes de transport existent sur Yadony ?")).isEqualTo("modes-transport");
        assertThat(firstHit(kb, "en", "Which transport modes exist on Yadony?")).isEqualTo("modes-transport");
    }
}
