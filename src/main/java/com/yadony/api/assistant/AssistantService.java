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
        String locale = "en".equals(request.locale()) ? "en" : "fr";
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
                hit.article().routes().stream().findFirst()
                        .ifPresent(r -> fallback.add(new AssistantAction(r.path(), r.label())));
            }
            return new AssistantAskResponse(DEGRADED.get(locale), false, fallback, sources, NO_PROVIDER, remaining);
        }

        ParsedAnswer parsed = result.get().value();
        String answer = parsed.answer().length() > MAX_ANSWER ? parsed.answer().substring(0, MAX_ANSWER) : parsed.answer();
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
        for (String route : routes) {
            if (actions.size() >= MAX_ACTIONS) break;
            if (!whitelist.contains(route) || !allowed.containsKey(route)) continue;
            if (actions.stream().anyMatch(a -> a.route().equals(route))) continue;
            actions.add(new AssistantAction(route, allowed.get(route)));
        }
        return actions;
    }

    /** Historique absent, nul ou partiel : on garde les 12 derniers tours valides, tronqués à 1000 caractères. */
    private static List<AssistantHistoryTurn> trimHistory(List<AssistantHistoryTurn> history) {
        if (history == null || history.isEmpty()) return List.of();
        List<AssistantHistoryTurn> last = history.subList(Math.max(0, history.size() - MAX_HISTORY), history.size());
        return last.stream()
                .filter(Objects::nonNull)
                .filter(t -> t.text() != null && ("user".equals(t.role()) || "assistant".equals(t.role())))
                .map(t -> new AssistantHistoryTurn(t.role(),
                        t.text().length() > MAX_TURN_LENGTH ? t.text().substring(0, MAX_TURN_LENGTH) : t.text()))
                .toList();
    }
}
