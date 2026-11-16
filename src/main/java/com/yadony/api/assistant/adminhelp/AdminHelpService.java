package com.yadony.api.assistant.adminhelp;

import com.yadony.api.assistant.AssistantAnswerParser;
import com.yadony.api.assistant.adminhelp.dto.AdminHelpArticleResponse;
import com.yadony.api.assistant.adminhelp.dto.AdminHelpArticleSummary;
import com.yadony.api.assistant.adminhelp.dto.AdminHelpAskRequest;
import com.yadony.api.assistant.adminhelp.dto.AdminHelpAskResponse;
import com.yadony.api.assistant.adminhelp.dto.AdminHelpCategoryResponse;
import com.yadony.api.assistant.adminhelp.dto.AdminHelpIndexResponse;
import com.yadony.api.assistant.adminhelp.dto.AdminHelpRoute;
import com.yadony.api.assistant.adminhelp.dto.AdminHelpSearchResponse;
import com.yadony.api.assistant.adminhelp.dto.AdminHelpSearchResult;
import com.yadony.api.assistant.adminhelp.dto.AdminHelpSource;
import com.yadony.api.assistant.dto.AssistantHistoryTurn;
import com.yadony.api.assistant.dto.ParsedAnswer;
import com.yadony.api.assistant.kb.KbRoute;
import com.yadony.api.assistant.llm.LlmResult;
import com.yadony.api.assistant.llm.LlmRouter;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.PlatformSettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Centre d'aide de la console : sommaire, fiche, recherche plein texte (sans IA, toujours disponible) et
 * assistant qui répond à partir des fiches. Une fiche reste lisible par tout admin ; seuls les boutons vers une
 * page qu'il ne peut pas ouvrir lui sont retirés.
 */
@Service
public class AdminHelpService {

    static final int MAX_HITS = 4;
    static final int MAX_SEARCH_RESULTS = 20;
    static final int MAX_RELATED = 4;
    static final int MAX_ACTIONS = 2;
    static final int MAX_HISTORY = 8;
    static final int MAX_TURN_LENGTH = 1000;
    static final int MAX_ANSWER = 1500;
    static final int MAX_SNIPPET = 220;
    static final int MAX_QUERY = 200;
    /** Recherche de la doc : une fiche doit valoir au moins ce score et ce quart du meilleur. */
    static final double SEARCH_MIN_SCORE = 1.0;
    static final double SEARCH_RELATIVE = 0.25;
    static final String NO_PROVIDER = "none";
    static final String NOT_FOUND = "Je n'ai pas trouvé ce sujet dans la documentation de la console. "
            + "Essayez avec d'autres mots, ou parcourez les rubriques du centre d'aide.";
    static final String DEGRADED = "Je ne peux pas rédiger de réponse pour l'instant. "
            + "Ces fiches de la documentation devraient vous aider :";
    static final AdminHelpRoute HELP_CENTER = new AdminHelpRoute("/aide", "Ouvrir le centre d'aide");

    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]\\n]*)]\\([^)\\n]*\\)");
    private static final Pattern MD_LINE_MARK = Pattern.compile("^(#{1,6}\\s+|>\\s*|[-*]\\s+|\\d{1,2}[.)]\\s+)+");

    private final AdminHelpIndex index;
    private final AdminHelpProperties properties;
    private final PlatformSettingsService settings;
    private final AdminHelpQuota quota;
    private final AdminHelpPromptBuilder prompts;
    private final AssistantAnswerParser parser;
    private final LlmRouter router;

    public AdminHelpService(AdminHelpIndex index, AdminHelpProperties properties, PlatformSettingsService settings,
                            AdminHelpQuota quota,
                            AdminHelpPromptBuilder prompts, AssistantAnswerParser parser, LlmRouter router) {
        this.index = index;
        this.properties = properties;
        this.settings = settings;
        this.quota = quota;
        this.prompts = prompts;
        this.parser = parser;
        this.router = router;
    }

    public AdminHelpIndexResponse contents(Set<String> permissions) {
        List<AdminHelpCategoryResponse> categories = AdminHelpLoader.CATEGORIES.entrySet().stream()
                .map(e -> new AdminHelpCategoryResponse(e.getKey(), e.getValue())).toList();
        List<AdminHelpArticleSummary> articles = index.all().stream().map(a -> summary(a, permissions)).toList();
        return new AdminHelpIndexResponse(categories, articles, settings.adminHelpAssistantEnabled());
    }

    public AdminHelpArticleResponse article(String id, Set<String> permissions) {
        AdminHelpArticle a = index.find(id).orElseThrow(() -> YadonyBusinessException.localized(HttpStatus.NOT_FOUND,
                "admin-help-article-not-found", "problem.admin-help.admin-help-article-not-found"));
        List<AdminHelpArticleSummary> related = index.search(a.title() + " " + String.join(" ", a.keywords()),
                        MAX_RELATED + 1, SEARCH_MIN_SCORE).stream()
                .map(AdminHelpIndex.Hit::article).filter(o -> !o.id().equals(a.id())).limit(MAX_RELATED)
                .map(o -> summary(o, permissions)).toList();
        return new AdminHelpArticleResponse(a.id(), a.title(), a.category(), AdminHelpLoader.CATEGORIES.get(a.category()),
                a.summary(), a.permission(), accessible(a, permissions), routes(a, permissions), a.body(), related);
    }

    /** Recherche plein texte dans la doc : aucun fournisseur, aucun quota. */
    public AdminHelpSearchResponse search(String rawQuery, Set<String> permissions) {
        String query = rawQuery == null ? "" : truncate(rawQuery.strip(), MAX_QUERY);
        if (query.isEmpty()) return new AdminHelpSearchResponse(query, List.of());
        List<AdminHelpIndex.Hit> hits = index.search(query, MAX_SEARCH_RESULTS, SEARCH_MIN_SCORE);
        if (hits.isEmpty()) return new AdminHelpSearchResponse(query, List.of());
        double floor = hits.get(0).score() * SEARCH_RELATIVE;
        Set<String> terms = index.terms(query);
        List<AdminHelpSearchResult> results = hits.stream().filter(h -> h.score() >= floor).map(h -> {
            AdminHelpArticle a = h.article();
            return new AdminHelpSearchResult(a.id(), a.title(), a.category(), a.summary(),
                    snippet(a, terms), accessible(a, permissions));
        }).toList();
        return new AdminHelpSearchResponse(query, results);
    }

    public AdminHelpAskResponse ask(UUID adminId, Set<String> permissions, AdminHelpAskRequest request) {
        if (!settings.adminHelpAssistantEnabled()) {
            throw YadonyBusinessException.localized(HttpStatus.NOT_FOUND, "admin-help-assistant-disabled",
                    "problem.admin-help.admin-help-assistant-disabled");
        }
        int remaining = quota.consume(adminId);
        String question = AdminHelpRedactor.redact(request.question().strip());
        List<AssistantHistoryTurn> history = trimHistory(request.history());

        List<AdminHelpIndex.Hit> hits = index.search(question, MAX_HITS, properties.minScore());
        if (hits.isEmpty()) {
            // Relance courte (« et pour un remboursement ? ») : la question précédente donne le sujet.
            Optional<String> previous = history.stream().filter(t -> "user".equals(t.role()))
                    .reduce((first, second) -> second).map(AssistantHistoryTurn::text);
            if (previous.isPresent()) {
                hits = index.search(question + "\n" + previous.get(), MAX_HITS, properties.minScore());
            }
        }
        if (hits.isEmpty()) {
            return new AdminHelpAskResponse(NOT_FOUND, false, List.of(HELP_CENTER), List.of(), NO_PROVIDER, remaining);
        }
        List<AdminHelpSource> sources = hits.stream()
                .map(h -> new AdminHelpSource(h.article().id(), h.article().title())).toList();

        Optional<LlmResult<ParsedAnswer>> result = router.complete(prompts.system(),
                prompts.user(hits, history, question), parser::parse);
        if (result.isEmpty()) {
            List<AdminHelpRoute> fallback = new ArrayList<>();
            for (AdminHelpIndex.Hit hit : hits) {
                if (fallback.size() >= MAX_ACTIONS) break;
                routes(hit.article(), permissions).stream().findFirst()
                        .filter(r -> fallback.stream().noneMatch(f -> f.path().equals(r.path())))
                        .ifPresent(fallback::add);
            }
            return new AdminHelpAskResponse(DEGRADED, false, fallback, sources, NO_PROVIDER, remaining);
        }

        ParsedAnswer parsed = result.get().value();
        String answer = truncate(AdminHelpPromptBuilder.clean(parsed.answer()).strip(), MAX_ANSWER);
        List<AdminHelpRoute> actions = filterActions(parsed.routes(), hits, permissions);
        if (actions.isEmpty() && parsed.answered()) {
            routes(hits.get(0).article(), permissions).stream().findFirst().ifPresent(r -> actions.add(r));
        }
        return new AdminHelpAskResponse(answer, parsed.answered(), List.copyOf(actions),
                parsed.answered() ? sources : List.of(), result.get().slug(), remaining);
    }

    /** Routes du modèle gardées seulement si elles figurent dans une fiche transmise et que l'admin peut les ouvrir. */
    private static List<AdminHelpRoute> filterActions(List<String> proposed, List<AdminHelpIndex.Hit> hits,
                                                      Set<String> permissions) {
        List<AdminHelpRoute> actions = new ArrayList<>();
        for (String raw : proposed) {
            if (actions.size() >= MAX_ACTIONS) break;
            String path = normalizeRoute(raw);
            if (actions.stream().anyMatch(a -> a.path().equals(path))) continue;
            hits.stream().flatMap(h -> h.article().routes().stream())
                    .filter(r -> r.path().equals(path) && AdminHelpPages.allowed(path, permissions))
                    .findFirst()
                    .ifPresent(r -> actions.add(new AdminHelpRoute(r.path(), r.label())));
        }
        return actions;
    }

    /** Certains modèles recopient le libellé après le chemin (« /colis (Colis) ») : on garde le chemin. */
    static String normalizeRoute(String raw) {
        if (raw == null) return "";
        String route = raw.strip();
        for (int i = 0; i < route.length(); i++) {
            char c = route.charAt(i);
            if (c == '(' || Character.isWhitespace(c)) return route.substring(0, i);
        }
        return route;
    }

    private static AdminHelpArticleSummary summary(AdminHelpArticle a, Set<String> permissions) {
        return new AdminHelpArticleSummary(a.id(), a.title(), a.category(), a.summary(), a.permission(),
                accessible(a, permissions), routes(a, permissions));
    }

    private static boolean accessible(AdminHelpArticle a, Set<String> permissions) {
        return a.permission() == null || permissions.contains(a.permission());
    }

    private static List<AdminHelpRoute> routes(AdminHelpArticle a, Set<String> permissions) {
        List<AdminHelpRoute> out = new ArrayList<>();
        for (KbRoute r : a.routes()) {
            if (AdminHelpPages.allowed(r.path(), permissions)) out.add(new AdminHelpRoute(r.path(), r.label()));
        }
        return out;
    }

    /** Phrase du corps (texte brut) qui contient le plus de termes cherchés ; le résumé si aucune. */
    String snippet(AdminHelpArticle a, Set<String> terms) {
        String best = null;
        int bestCount = 0;
        for (String line : a.body().split("\n")) {
            if (line.stripLeading().startsWith("#")) continue;
            String plain = plain(line);
            if (plain.isEmpty()) continue;
            for (String sentence : plain.split("(?<=[.!?:])\\s+")) {
                Set<String> found = index.terms(sentence);
                found.retainAll(terms);
                if (found.size() > bestCount) {
                    bestCount = found.size();
                    best = sentence;
                }
            }
        }
        return best == null ? a.summary() : truncate(best, MAX_SNIPPET);
    }

    /** Une ligne de Markdown restreint en texte brut : marques de début de ligne, gras et liens retirés. */
    static String plain(String line) {
        String text = MD_LINE_MARK.matcher(line.strip()).replaceFirst("");
        text = MD_LINK.matcher(text).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(m.group(1)));
        return text.replace("**", "").strip();
    }

    private static List<AssistantHistoryTurn> trimHistory(List<AssistantHistoryTurn> history) {
        if (history == null || history.isEmpty()) return List.of();
        List<AssistantHistoryTurn> valid = history.stream().filter(Objects::nonNull)
                .filter(t -> t.text() != null && ("user".equals(t.role()) || "assistant".equals(t.role())))
                .toList();
        return valid.subList(Math.max(0, valid.size() - MAX_HISTORY), valid.size()).stream()
                .map(t -> new AssistantHistoryTurn(t.role(),
                        AdminHelpRedactor.redact(truncate(t.text(), MAX_TURN_LENGTH))))
                .toList();
    }

    /** Tronque sans couper une paire de substitution (emoji) ; ajoute « … » quand le texte est coupé. */
    static String truncate(String text, int max) {
        if (text.length() <= max) return text;
        int end = max - 1;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end).stripTrailing() + "…";
    }
}
