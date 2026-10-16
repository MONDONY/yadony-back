package com.yadony.api.assistant;

import com.yadony.api.assistant.dto.AssistantAction;
import com.yadony.api.assistant.dto.AssistantAskRequest;
import com.yadony.api.assistant.dto.AssistantAskResponse;
import com.yadony.api.assistant.dto.AssistantHistoryTurn;
import com.yadony.api.assistant.dto.ParsedAnswer;
import com.yadony.api.assistant.kb.KbHit;
import com.yadony.api.assistant.kb.KbRoute;
import com.yadony.api.assistant.kb.KnowledgeBase;
import com.yadony.api.assistant.llm.LlmResult;
import com.yadony.api.assistant.llm.LlmRouter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Une question : quota, fiches, fournisseur, filtrage des routes (spec §3.3 à §3.5). */
@Service
public class AssistantService {

    static final int MAX_HITS = 4;
    static final int MAX_HISTORY = 12;
    static final int MAX_TURN_LENGTH = 1000;
    static final int MAX_ANSWER = 600;
    static final int MAX_ACTIONS = 2;
    static final String NO_PROVIDER = "none";

    private static final Map<String, String> DEGRADED = Map.of(
            "fr", "Je n'arrive pas à répondre pour l'instant. Voici ce qui peut vous aider :",
            "en", "I can't answer right now. Here is what may help:");
    private static final Map<String, String> NOT_FOUND = Map.of(
            "fr", "Je n'ai pas trouvé cette information dans l'aide de Yadony. L'équipe peut vous répondre.",
            "en", "I could not find this in the Yadony help. The team can answer you.");

    private final AssistantQuotaGuard quota;
    private final KnowledgeBase knowledgeBase;
    private final AssistantPromptBuilder promptBuilder;
    private final AssistantAnswerParser parser;
    private final LlmRouter router;

    public AssistantService(AssistantQuotaGuard quota, KnowledgeBase knowledgeBase, AssistantPromptBuilder promptBuilder,
                            AssistantAnswerParser parser, LlmRouter router) {
        this.quota = quota;
        this.knowledgeBase = knowledgeBase;
        this.promptBuilder = promptBuilder;
        this.parser = parser;
        this.router = router;
    }

    public AssistantAskResponse ask(String uid, AssistantAskRequest request) {
        int remaining = quota.consume(uid);
        String locale = normalizeLocale(request.locale());
        String question = request.question().strip();
        List<AssistantHistoryTurn> history = trimHistory(request.history());
        String previous = history.stream().filter(t -> "user".equals(t.role()))
                .reduce((first, second) -> second).map(AssistantHistoryTurn::text).orElse(null);

        List<KbHit> hits = knowledgeBase.search(locale, question, previous, MAX_HITS);
        List<String> sources = hits.stream().map(h -> h.article().id()).toList();
        if (hits.isEmpty()) {
            return new AssistantAskResponse(NOT_FOUND.get(locale), false, List.of(), List.of(), NO_PROVIDER, remaining);
        }

        Optional<LlmResult<ParsedAnswer>> result = router.complete(
                promptBuilder.system(), promptBuilder.user(locale, hits, history, question), parser::parse);
        if (result.isEmpty()) {
            List<AssistantAction> fallback = new ArrayList<>();
            for (KbHit hit : hits) {
                if (fallback.size() >= MAX_ACTIONS) break;
                Optional<KbRoute> first = hit.article().routes().stream().findFirst();
                if (first.isEmpty() || fallback.stream().anyMatch(a -> a.route().equals(first.get().path()))) continue;
                fallback.add(new AssistantAction(first.get().path(), first.get().label()));
            }
            return new AssistantAskResponse(DEGRADED.get(locale), false, fallback, sources, NO_PROVIDER, remaining);
        }

        ParsedAnswer parsed = result.get().value();
        String answer = truncate(parsed.answer(), MAX_ANSWER);
        return new AssistantAskResponse(answer, parsed.answered(), filterActions(parsed.routes(), hits), sources,
                result.get().slug(), remaining);
    }

    /** Garde une route seulement si elle est dans la liste autorisée ET dans une fiche transmise ; label de la fiche. */
    private List<AssistantAction> filterActions(List<String> routes, List<KbHit> hits) {
        Map<String, String> allowed = new LinkedHashMap<>();
        for (KbHit hit : hits) {
            for (KbRoute r : hit.article().routes()) allowed.putIfAbsent(r.path(), r.label());
        }
        Set<String> whitelist = knowledgeBase.routeWhitelist();
        List<AssistantAction> actions = new ArrayList<>();
        for (String raw : routes) {
            if (actions.size() >= MAX_ACTIONS) break;
            String route = normalizeRoute(raw);
            if (!whitelist.contains(route) || !allowed.containsKey(route)) continue;
            if (actions.stream().anyMatch(a -> a.route().equals(route))) continue;
            actions.add(new AssistantAction(route, allowed.get(route)));
        }
        return actions;
    }

    /**
     * Certains modèles recopient le libellé de la fiche après le chemin (« /payments/money (My money) ») :
     * on ne garde que le chemin, avant tout blanc ou parenthèse. Le filtre de liste blanche reste juge.
     */
    private static String normalizeRoute(String raw) {
        if (raw == null) return "";
        String route = raw.strip();
        int cut = route.length();
        for (int i = 0; i < route.length(); i++) {
            char c = route.charAt(i);
            if (c == '(' || Character.isWhitespace(c)) {
                cut = i;
                break;
            }
        }
        return route.substring(0, cut);
    }

    /** Langue : « en », « EN » ou « en-US » donnent anglais ; tout le reste français. */
    private static String normalizeLocale(String locale) {
        return locale != null && locale.strip().toLowerCase(Locale.ROOT).startsWith("en") ? "en" : "fr";
    }

    /** Historique absent ou nul : vide. Sinon on filtre les tours valides, on garde les 12 derniers, puis on tronque. */
    private static List<AssistantHistoryTurn> trimHistory(List<AssistantHistoryTurn> history) {
        if (history == null || history.isEmpty()) return List.of();
        List<AssistantHistoryTurn> valid = history.stream()
                .filter(Objects::nonNull)
                .filter(t -> t.text() != null && ("user".equals(t.role()) || "assistant".equals(t.role())))
                .toList();
        return valid.subList(Math.max(0, valid.size() - MAX_HISTORY), valid.size()).stream()
                .map(t -> new AssistantHistoryTurn(t.role(), truncate(t.text(), MAX_TURN_LENGTH)))
                .toList();
    }

    /** Tronque à max unités UTF-16 sans couper une paire de substitution (emoji). */
    private static String truncate(String text, int max) {
        if (text.length() <= max) return text;
        int end = max;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }
}
