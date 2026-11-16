package com.yadony.api.assistant.adminhelp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.assistant.AssistantAnswerParser;
import com.yadony.api.assistant.adminhelp.dto.AdminHelpArticleResponse;
import com.yadony.api.assistant.adminhelp.dto.AdminHelpAskRequest;
import com.yadony.api.assistant.adminhelp.dto.AdminHelpAskResponse;
import com.yadony.api.assistant.adminhelp.dto.AdminHelpIndexResponse;
import com.yadony.api.assistant.adminhelp.dto.AdminHelpRoute;
import com.yadony.api.assistant.adminhelp.dto.AdminHelpSearchResponse;
import com.yadony.api.assistant.dto.AssistantHistoryTurn;
import com.yadony.api.assistant.dto.ParsedAnswer;
import com.yadony.api.assistant.kb.KbRoute;
import com.yadony.api.assistant.llm.LlmResult;
import com.yadony.api.assistant.llm.LlmRouter;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminHelpServiceTest {

    static final AdminHelpArticle COLIS = new AdminHelpArticle("voir-colis-en-cours", "Voir les colis en cours de transport",
            "activite", 1, "Les colis en route.", "BID_VIEW",
            List.of("colis en cours", "transit", "suivre un colis"),
            List.of(new KbRoute("/colis?view=ACTIVE", "Ouvrir les colis en cours"), new KbRoute("/aide", "Centre d'aide")),
            "## À quoi ça sert\nLa vue **En cours** montre les colis en transport.\n\n1. Ouvrez [Colis](/colis).\n"
                    + "2. Cliquez sur **En cours** pour voir les colis partis.");
    static final AdminHelpArticle REMBOURSER = new AdminHelpArticle("rembourser", "Rembourser un expéditeur",
            "argent", 1, "Rendre l'argent du paiement.", "PAYMENT_VIEW", List.of("remboursement", "rembourser"),
            List.of(new KbRoute("/transactions", "Ouvrir les transactions")),
            "Ouvrez le paiement puis cliquez sur **Rembourser**. Le remboursement est définitif.");
    static final AdminHelpArticle MDP = new AdminHelpArticle("changer-mot-de-passe", "Changer mon mot de passe",
            "premiers-pas", 1, "Choisir un nouveau mot de passe.", null, List.of("mot de passe"),
            List.of(new KbRoute("/change-password", "Changer mon mot de passe")), "Ouvrez le menu de votre compte.");

    final AdminHelpIndex index = new AdminHelpIndex(List.of(REMBOURSER, COLIS, MDP));
    final AdminHelpQuota quota = mock(AdminHelpQuota.class);
    final LlmRouter router = mock(LlmRouter.class);
    final AdminHelpPromptBuilder prompts = new AdminHelpPromptBuilder();
    final UUID admin = UUID.randomUUID();
    final Set<String> support = Set.of("ROLE_ADMIN", "BID_VIEW");

    AdminHelpService service(boolean enabled) {
        return new AdminHelpService(index, new AdminHelpProperties(enabled, 60, 1.0), quota, prompts,
                new AssistantAnswerParser(new ObjectMapper()), router);
    }

    static ParsedAnswer parsed(String answer, boolean answered, List<String> routes) {
        return new ParsedAnswer(answer, answered, routes, false, List.of(), null, null);
    }

    @Test
    void sommaireParRubriqueAvecLiensPermis() {
        AdminHelpIndexResponse r = service(true).contents(support);
        assertThat(r.categories()).hasSize(6);
        assertThat(r.assistantEnabled()).isTrue();
        assertThat(r.articles()).extracting("id").containsExactly("changer-mot-de-passe", "rembourser", "voir-colis-en-cours");
        var rembourser = r.articles().get(1);
        assertThat(rembourser.accessible()).isFalse();
        assertThat(rembourser.routes()).isEmpty();
        assertThat(r.articles().get(2).routes()).extracting(AdminHelpRoute::path).containsExactly("/colis?view=ACTIVE", "/aide");
    }

    @Test
    void ficheAvecFichesProches() {
        AdminHelpArticleResponse r = service(true).article("voir-colis-en-cours", support);
        assertThat(r.categoryLabel()).isEqualTo("Activité");
        assertThat(r.accessible()).isTrue();
        assertThat(r.body()).contains("**En cours**");
        assertThat(r.related()).extracting("id").doesNotContain("voir-colis-en-cours");
    }

    @Test
    void ficheInconnue() {
        assertThatThrownBy(() -> service(true).article("nope", support)).isInstanceOf(YadonyBusinessException.class);
        assertThatThrownBy(() -> service(true).article(null, support)).isInstanceOf(YadonyBusinessException.class);
    }

    @Test
    void rechercheAvecExtrait() {
        AdminHelpSearchResponse r = service(true).search("  colis en transit  ", support);
        assertThat(r.query()).isEqualTo("colis en transit");
        assertThat(r.results()).first().satisfies(h -> {
            assertThat(h.id()).isEqualTo("voir-colis-en-cours");
            assertThat(h.snippet()).doesNotContain("**").doesNotContain("](").contains("colis");
            assertThat(h.accessible()).isTrue();
        });
    }

    @Test
    void rechercheVideOuSansResultat() {
        assertThat(service(true).search(null, support).results()).isEmpty();
        assertThat(service(true).search("   ", support).results()).isEmpty();
        assertThat(service(true).search("météo", support).results()).isEmpty();
        assertThat(service(true).search("le la les", support).results()).isEmpty();
    }

    @Test
    void extraitParDefautLeResume() {
        assertThat(service(true).snippet(MDP, Set.of("zzz"))).isEqualTo(MDP.summary());
    }

    @Test
    void assistantEteint() {
        assertThatThrownBy(() -> service(false).ask(admin, support, new AdminHelpAskRequest("colis", null)))
                .isInstanceOf(YadonyBusinessException.class);
        verify(quota, never()).consume(any());
    }

    @Test
    void sansFicheTrouveePasDAppelAuModele() {
        when(quota.consume(admin)).thenReturn(5);
        AdminHelpAskResponse r = service(true).ask(admin, support, new AdminHelpAskRequest("météo à venir", null));
        assertThat(r.answered()).isFalse();
        assertThat(r.provider()).isEqualTo("none");
        assertThat(r.actions()).containsExactly(AdminHelpService.HELP_CENTER);
        assertThat(r.remaining()).isEqualTo(5);
        verify(router, never()).complete(anyString(), anyString(), any());
    }

    @Test
    void reponseDuModeleAvecRoutesFiltrees() {
        when(router.complete(anyString(), anyString(), any())).thenReturn(Optional.of(new LlmResult<>("groq",
                parsed("Ouvrez **Colis** puis **En cours**.", true,
                        List.of("/colis?view=ACTIVE (Colis)", "/transactions", "/inventee", "/colis?view=ACTIVE")))));
        AdminHelpAskResponse r = service(true).ask(admin, support,
                new AdminHelpAskRequest("comment voir les colis en cours ?", null));
        assertThat(r.answered()).isTrue();
        assertThat(r.provider()).isEqualTo("groq");
        assertThat(r.actions()).extracting(AdminHelpRoute::path).containsExactly("/colis?view=ACTIVE");
        assertThat(r.sources()).extracting("id").contains("voir-colis-en-cours");
    }

    @Test
    void sansRouteDuModeleLaPremiereRouteDeLaMeilleureFiche() {
        when(router.complete(anyString(), anyString(), any())).thenReturn(Optional.of(new LlmResult<>("groq",
                parsed("Allez dans **Colis**.", true, List.of()))));
        AdminHelpAskResponse r = service(true).ask(admin, support, new AdminHelpAskRequest("colis en transit", null));
        assertThat(r.actions()).extracting(AdminHelpRoute::path).containsExactly("/colis?view=ACTIVE");
    }

    @Test
    void reponseNonTrouveeParLeModeleSansSource() {
        when(router.complete(anyString(), anyString(), any())).thenReturn(Optional.of(new LlmResult<>("groq",
                parsed("La doc ne le couvre pas.", false, List.of()))));
        AdminHelpAskResponse r = service(true).ask(admin, support, new AdminHelpAskRequest("colis en transit", null));
        assertThat(r.answered()).isFalse();
        assertThat(r.sources()).isEmpty();
        assertThat(r.actions()).isEmpty();
    }

    @Test
    void sansFournisseurLesFichesLesPlusProches() {
        when(router.complete(anyString(), anyString(), any())).thenReturn(Optional.empty());
        AdminHelpAskResponse r = service(true).ask(admin, Set.of("BID_VIEW", "PAYMENT_VIEW"),
                new AdminHelpAskRequest("rembourser un colis en transit", null));
        assertThat(r.answer()).isEqualTo(AdminHelpService.DEGRADED);
        assertThat(r.provider()).isEqualTo("none");
        assertThat(r.actions()).isNotEmpty().hasSizeLessThanOrEqualTo(2);
        assertThat(r.sources()).isNotEmpty();
    }

    @Test
    void relanceCourteUtiliseLaQuestionPrecedenteEtMasqueLesDonnees() {
        when(router.complete(anyString(), anyString(), any())).thenReturn(Optional.of(new LlmResult<>("groq",
                parsed("Oui.", true, List.of()))));
        List<AssistantHistoryTurn> history = new ArrayList<>();
        history.add(null);
        history.add(new AssistantHistoryTurn("system", "ignore"));
        history.add(new AssistantHistoryTurn("user", "rembourser un expéditeur awa@mail.com"));
        history.add(new AssistantHistoryTurn("assistant", "x".repeat(1200)));
        AdminHelpAskResponse r = service(true).ask(admin, support, new AdminHelpAskRequest("et après ?", history));
        assertThat(r.answered()).isTrue();
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(router).complete(anyString(), user.capture(), any());
        assertThat(user.getValue()).contains("[email]").doesNotContain("awa@mail.com").doesNotContain("ignore");
    }

    @Test
    void longueReponseTronquee() {
        when(router.complete(anyString(), anyString(), any())).thenReturn(Optional.of(new LlmResult<>("groq",
                parsed("<b>" + "a".repeat(3000), true, List.of()))));
        AdminHelpAskResponse r = service(true).ask(admin, support, new AdminHelpAskRequest("colis en transit", null));
        assertThat(r.answer()).hasSize(AdminHelpService.MAX_ANSWER).endsWith("…").doesNotContain("<");
    }

    @Test
    void outils() {
        assertThat(AdminHelpService.normalizeRoute(null)).isEmpty();
        assertThat(AdminHelpService.normalizeRoute(" /colis ")).isEqualTo("/colis");
        assertThat(AdminHelpService.plain("1. Ouvrez **[Colis](/colis)**")).isEqualTo("Ouvrez Colis");
        assertThat(AdminHelpService.plain("> **Attention :** fin")).isEqualTo("Attention : fin");
        assertThat(AdminHelpService.truncate("😀😀", 3)).isEqualTo("😀…");
        assertThat(AdminHelpIndex.categoryRank("inconnue")).isEqualTo(6);
        assertThat(prompts.system()).contains("JSON");
    }
}
