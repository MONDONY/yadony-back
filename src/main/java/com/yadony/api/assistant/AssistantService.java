package com.yadony.api.assistant;

import com.yadony.api.assistant.dto.AssistantAction;
import com.yadony.api.assistant.dto.AssistantAskRequest;
import com.yadony.api.assistant.dto.AssistantAskResponse;
import com.yadony.api.assistant.dto.AssistantHistoryTurn;
import com.yadony.api.assistant.dto.AssistantScreenResponse;
import com.yadony.api.assistant.dto.AssistantSuggestion;
import com.yadony.api.assistant.dto.ParsedAnswer;
import com.yadony.api.assistant.kb.KbArticle;
import com.yadony.api.assistant.kb.KbHit;
import com.yadony.api.assistant.kb.KbRoute;
import com.yadony.api.assistant.kb.KnowledgeBase;
import com.yadony.api.assistant.llm.LlmResult;
import com.yadony.api.assistant.llm.LlmRouter;
import com.yadony.api.assistant.parcel.AssistantParcelFlow;
import com.yadony.api.assistant.parcel.TrackingNumbers;
import com.yadony.api.common.assistant.ParcelFacts;
import com.yadony.api.common.assistant.ParcelRef;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Une question : quota, fiches, fournisseur, filtrage des routes (spec §3.3 à §3.5). Quand la question
 * porte sur un colis de l'utilisateur, ses faits sont ajoutés, mais ce qui dépend de ses données
 * (numéro incorrect, aucun colis, choix du colis, étape) est écrit par le back, jamais par le modèle.
 */
@Service
public class AssistantService {

    static final int MAX_HITS = 4;
    static final int MAX_HISTORY = 12;
    static final int MAX_TURN_LENGTH = 1000;
    static final int MAX_ANSWER = 600;
    static final int MAX_ACTIONS = 2;
    /** Questions utilisateur précédentes ajoutées à la recherche de relance (jamais les réponses de l'assistant). */
    static final int MAX_CONTEXT_QUESTIONS = 2;
    /** Même plafond que la question posée (AssistantAskRequest) : un tour plus long ne pèse pas plus dans la recherche. */
    static final int MAX_CONTEXT_QUESTION_LENGTH = 500;
    static final String NO_PROVIDER = "none";
    static final int MAX_FOLLOW_UPS = 2;
    static final int MAX_SUGGESTION_LENGTH = 80;
    /** Fiches proposées au modèle pour la suggestion « découverte » : assez pour varier, peu pour le prompt. */
    static final int DISCOVERY_CANDIDATES = 5;

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
    private final AssistantParcelFlow parcels;

    public AssistantService(AssistantQuotaGuard quota, KnowledgeBase knowledgeBase, AssistantPromptBuilder promptBuilder,
                            AssistantAnswerParser parser, LlmRouter router, AssistantParcelFlow parcels) {
        this.quota = quota;
        this.knowledgeBase = knowledgeBase;
        this.promptBuilder = promptBuilder;
        this.parser = parser;
        this.router = router;
        this.parcels = parcels;
    }

    public AssistantAskResponse ask(String uid, AssistantAskRequest request) {
        int remaining = quota.consume(uid);
        String locale = normalizeLocale(request.locale());
        String question = request.question().strip();
        List<AssistantHistoryTurn> history = trimHistory(request.history());
        String previous = history.stream().filter(t -> "user".equals(t.role()))
                .reduce((first, second) -> second).map(AssistantHistoryTurn::text).orElse(null);


        // Le numéro écrit, sinon le colis choisi plus tôt : l'accès est revérifié à chaque question.
        AssistantParcelFlow.Lookup lookup = parcels.lookup(uid, question, request.parcelId());
        if (lookup.incorrect()) {
            return new AssistantAskResponse(AssistantParcelFlow.incorrect(locale), false, List.of(), List.of(),
                    NO_PROVIDER, remaining);
        }
        ParcelRef parcel = lookup.parcel();

        // Le numéro ne figure dans aucune fiche, ni dans ce qui part vers le fournisseur.
        String searched = TrackingNumbers.strip(question);
        String searchedPrevious = previous == null ? null : TrackingNumbers.strip(previous);
        List<AssistantHistoryTurn> searchedHistory = history.stream()
                .map(t -> new AssistantHistoryTurn(t.role(), TrackingNumbers.strip(t.text()))).toList();
        List<KbHit> hits = List.of();
        boolean otherLanguage = false;
        if (!searched.isEmpty()) {
            hits = searchWithContext(locale, searched, searchedPrevious, searchedHistory);
            // Téléphone en anglais mais question écrite en français (FLUTTER-QV, appareil en-FR), ou l'inverse :
            // rien dans l'index de la langue de l'interface, on refait les mêmes recherches dans l'autre langue.
            if (hits.isEmpty()) {
                hits = searchWithContext(otherLocale(locale), searched, searchedPrevious, searchedHistory);
                otherLanguage = !hits.isEmpty();
            }
        }
        List<String> sources = hits.stream().map(h -> h.article().id()).toList();
        if (parcel != null && hits.isEmpty()) {
            return parcelStatus(parcel, locale, sources, remaining);
        }
        if (hits.isEmpty()) {
            return new AssistantAskResponse(NOT_FOUND.get(locale), false, List.of(), List.of(), NO_PROVIDER, remaining);
        }

        Map<String, AssistantAction> parcelActions = Map.of();
        AssistantPromptBuilder.ParcelPrompt parcelPrompt = null;
        if (parcel != null) {
            ParcelFacts facts = parcels.facts(parcel);
            parcelActions = parcels.actions(parcel, facts, locale);
            Map<String, String> routes = new LinkedHashMap<>();
            parcelActions.forEach((token, action) -> routes.put(token, action.label()));
            parcelPrompt = new AssistantPromptBuilder.ParcelPrompt(facts.lines(), routes);
        }
        List<AssistantHistoryTurn> maskedHistory = history.stream()
                .map(t -> new AssistantHistoryTurn(t.role(), TrackingNumbers.mask(t.text()))).toList();

        // Fiches de la langue de la réponse : celles des fiches trouvées.
        String hitsLocale = otherLanguage ? otherLocale(locale) : locale;
        List<KbArticle> discover = knowledgeBase.discoveryCandidates(hitsLocale, Set.copyOf(sources), searched,
                DISCOVERY_CANDIDATES);
        Optional<LlmResult<ParsedAnswer>> result = router.complete(promptBuilder.system(),
                promptBuilder.user(locale, hits, maskedHistory, TrackingNumbers.mask(question), otherLanguage,
                        parcelPrompt, discover),
                parser::parse);
        if (result.isEmpty() && parcel != null) {
            // Sans fournisseur, l'étape du colis reste une vraie réponse.
            return parcelStatus(parcel, locale, sources, remaining);
        }
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
        String slug = result.get().slug();
        if (parcel == null && parsed.aboutMyParcel()) {
            return chooseParcel(uid, locale, sources, slug, remaining);
        }
        String answer = truncate(parsed.answer(), MAX_ANSWER);
        return new AssistantAskResponse(answer, parsed.answered(), filterActions(parsed.routes(), hits, parcelActions),
                sources, slug, remaining, parcel == null ? null : parcel.bidId(), List.of(),
                parsed.answered() ? suggestions(parsed, discover, question, history) : List.of());
    }

    /**
     * Suggestions du modèle, passées au crible : au plus {@value #MAX_FOLLOW_UPS} questions de suite puis une
     * « découverte » dont la fiche figure parmi celles proposées ({@code discover}). Vides, trop longues,
     * en double, ou déjà posées dans la conversation : retirées, sans jamais faire échouer la réponse.
     */
    static List<AssistantSuggestion> suggestions(ParsedAnswer parsed, List<KbArticle> discover, String question,
                                                 List<AssistantHistoryTurn> history) {
        Set<String> seen = new java.util.HashSet<>();
        seen.add(key(question));
        for (AssistantHistoryTurn t : history) if ("user".equals(t.role())) seen.add(key(t.text()));
        List<AssistantSuggestion> out = new ArrayList<>();
        for (String raw : parsed.followUps()) {
            if (out.size() >= MAX_FOLLOW_UPS) break;
            String text = cleanSuggestion(raw);
            if (text != null && seen.add(key(text))) out.add(new AssistantSuggestion(text, AssistantSuggestion.FOLLOW_UP));
        }
        String id = parsed.discoveryId() == null ? null : parsed.discoveryId().strip();
        boolean known = id != null && discover.stream().anyMatch(a -> a.id().equals(id));
        String text = known ? cleanSuggestion(parsed.discoveryQuestion()) : null;
        if (text != null && seen.add(key(text))) out.add(new AssistantSuggestion(text, AssistantSuggestion.DISCOVERY));
        return List.copyOf(out);
    }

    /** Une ligne, blancs resserrés, sans tiret cadratin ni chevron ; null si vide ou plus long que 80 caractères. */
    private static String cleanSuggestion(String raw) {
        if (raw == null) return null;
        String text = raw.replace(" — ", ", ").replace(" – ", ", ").replace('—', '-').replace('–', '-')
                .replace('<', ' ').replace('>', ' ')
                .replaceAll("\\s+", " ").strip();
        if (text.isEmpty() || text.length() > MAX_SUGGESTION_LENGTH) return null;
        return text;
    }

    /** Clé de comparaison : casse, blancs et ponctuation finale ignorés. */
    private static String key(String text) {
        if (text == null) return "";
        return text.strip().toLowerCase(Locale.ROOT).replaceAll("[\\s?!.¿¡]+$", "").replaceAll("\\s+", " ");
    }

    /**
     * Recherche de fiches. D'abord comme avant : la question, plus la dernière question utilisateur à demi-poids
     * si la question est courte. Puis, si rien ne passe le seuil, ou si la question est une relance courte qui
     * ramène moins de {@value #MAX_HITS} fiches, une seconde recherche sur la question suivie des
     * {@value #MAX_CONTEXT_QUESTIONS} dernières questions utilisateur distinctes, à plein poids (« et pour un colis
     * de 10 kg ? » seule ne passe pas le seuil, FLUTTER-QV). Les fiches de la première recherche restent en tête,
     * celles de la seconde complètent sans doublon, dans la limite de {@value #MAX_HITS}.
     */
    private List<KbHit> searchWithContext(String locale, String question, String previous,
                                          List<AssistantHistoryTurn> history) {
        List<KbHit> hits = knowledgeBase.search(locale, question, previous, MAX_HITS);
        if (hits.size() >= MAX_HITS || (!hits.isEmpty() && !knowledgeBase.isShortFollowUp(locale, question))) {
            return hits;
        }
        List<String> context = previousUserQuestions(history, question);
        if (context.isEmpty()) {
            return hits;
        }
        String combined = question + "\n" + String.join("\n", context);
        Map<String, KbHit> merged = new LinkedHashMap<>();
        for (KbHit hit : hits) merged.putIfAbsent(hit.article().id(), hit);
        for (KbHit hit : knowledgeBase.search(locale, combined, null, MAX_HITS)) {
            if (merged.size() >= MAX_HITS) break;
            merged.putIfAbsent(hit.article().id(), hit);
        }
        return List.copyOf(merged.values());
    }

    /** Dernières questions utilisateur, la plus récente d'abord, sans tour vide ni répétition de la question posée. */
    private static List<String> previousUserQuestions(List<AssistantHistoryTurn> history, String question) {
        List<String> questions = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && questions.size() < MAX_CONTEXT_QUESTIONS; i--) {
            AssistantHistoryTurn turn = history.get(i);
            if (!"user".equals(turn.role())) continue;
            String text = truncate(turn.text().strip(), MAX_CONTEXT_QUESTION_LENGTH);
            if (text.isBlank() || text.equalsIgnoreCase(question)) continue;
            if (questions.stream().anyMatch(text::equalsIgnoreCase)) continue;
            questions.add(text);
        }
        return questions;
    }

    /** Explication d'un écran, écrite dans sa fiche ; ses autres routes, hors l'écran lui-même, en boutons. */
    public Optional<AssistantScreenResponse> screen(String path, String locale) {
        // Un motif de route tient en 120 caractères (KnowledgeBaseLoader) : au-delà, aucun écran.
        if (path == null || path.isBlank() || path.length() > 120) return Optional.empty();
        String lang = normalizeLocale(locale);
        Set<String> whitelist = knowledgeBase.routeWhitelist();
        return knowledgeBase.screen(lang, path.strip()).map(a -> new AssistantScreenResponse(a.id(), a.title(),
                a.body(), a.questions(), a.routes().stream()
                .filter(r -> whitelist.contains(r.path()) && !a.screens().contains(r.path()))
                .limit(MAX_ACTIONS)
                .map(r -> new AssistantAction(r.path(), r.label())).toList()));
    }

    /** La question vise l'un de ses colis sans dire lequel : aucun colis, ou les siens à choisir. */
    private AssistantAskResponse chooseParcel(String uid, String locale, List<String> sources, String slug, int remaining) {
        List<ParcelRef> choices = parcels.choices(uid);
        if (choices.isEmpty()) {
            return new AssistantAskResponse(AssistantParcelFlow.noParcel(locale), true, List.of(), sources, slug,
                    remaining);
        }
        return new AssistantAskResponse(AssistantParcelFlow.choose(choices, locale), true, List.of(), sources, slug,
                remaining, null, choices.stream().map(AssistantParcelFlow::choice).toList());
    }

    /** Étape du colis, écrite par le back, et sa fiche. */
    private AssistantAskResponse parcelStatus(ParcelRef parcel, String locale, List<String> sources, int remaining) {
        return new AssistantAskResponse(AssistantParcelFlow.status(parcel, locale), true,
                List.of(parcels.open(parcel, locale)), sources, NO_PROVIDER, remaining, parcel.bidId(), List.of());
    }

    /**
     * Garde une route seulement si elle est dans la liste autorisée ET dans une fiche transmise ; label de la fiche.
     * Les repères du colis ({@code parcel:open}…) deviennent ses vrais chemins, construits par le back.
     */
    private List<AssistantAction> filterActions(List<String> routes, List<KbHit> hits,
                                                Map<String, AssistantAction> parcelActions) {
        Map<String, String> allowed = new LinkedHashMap<>();
        for (KbHit hit : hits) {
            for (KbRoute r : hit.article().routes()) allowed.putIfAbsent(r.path(), r.label());
        }
        Set<String> whitelist = knowledgeBase.routeWhitelist();
        List<AssistantAction> actions = new ArrayList<>();
        for (String raw : routes) {
            if (actions.size() >= MAX_ACTIONS) break;
            String route = normalizeRoute(raw);
            AssistantAction action = parcelActions.get(route);
            if (action == null) {
                if (!whitelist.contains(route) || !allowed.containsKey(route)) continue;
                action = new AssistantAction(route, allowed.get(route));
            }
            String path = action.route();
            if (actions.stream().anyMatch(a -> a.route().equals(path))) continue;
            actions.add(action);
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

    /** Les fiches existent en français et en anglais, avec les mêmes identifiants et les mêmes routes. */
    private static String otherLocale(String locale) {
        return "en".equals(locale) ? "fr" : "en";
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
