package com.yadony.api.assistant.formdraft;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.assistant.formdraft.dto.FormDraftRequest;
import com.yadony.api.assistant.formdraft.dto.FormDraftResponse;
import com.yadony.api.assistant.formdraft.dto.FormDraftTurn;
import com.yadony.api.assistant.llm.LlmResult;
import com.yadony.api.assistant.llm.LlmRouter;
import com.yadony.api.common.YadonyBusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Un tour de dialogue d'un formulaire rempli par l'IA (spec §3.3, §3.4). Le modèle propose, le schéma valide,
 * le serveur décide de ce qui manque et de la fin. Ni les messages ni le brouillon ne sont journalisés.
 */
@Service
public class FormDraftEngine {

    static final int MAX_QUESTION = 300;
    static final int MAX_QUICK_REPLIES = 3;
    static final int MAX_QUICK_REPLY = 40;
    private static final Map<String, String> CONFIRM = Map.of("fr", "Est-ce bien cela ?", "en", "Is that right?");

    private final FormDraftQuotaGuard quota;
    private final FormDraftProperties properties;
    private final FormDraftParser parser;
    private final LlmRouter router;
    private final MeterRegistry registry;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public FormDraftEngine(FormDraftQuotaGuard quota, FormDraftProperties properties, FormDraftParser parser,
                           LlmRouter router, MeterRegistry registry) {
        this(quota, properties, parser, router, registry, Clock.systemUTC());
    }

    FormDraftEngine(FormDraftQuotaGuard quota, FormDraftProperties properties, FormDraftParser parser,
                    LlmRouter router, MeterRegistry registry, Clock clock) {
        this.quota = quota;
        this.properties = properties;
        this.parser = parser;
        this.router = router;
        this.registry = registry;
        this.clock = clock;
    }

    public FormDraftResponse run(FormDraftSchema schema, String uid, FormDraftRequest request) {
        List<FormDraftTurn> turns = request.messages();
        long userTurns = turns.stream().filter(t -> "user".equals(t.role())).count();
        if (userTurns > properties.maxTurns()) {
            throw new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "form-draft-too-many-turns",
                    "Unprocessable Entity", "La conversation est trop longue : remplissez le formulaire.");
        }
        if (!"user".equals(turns.get(turns.size() - 1).role())) {
            throw new YadonyBusinessException(HttpStatus.BAD_REQUEST, "form-draft-last-turn-not-user",
                    "Bad Request", "Le dernier message doit venir de l'utilisateur.");
        }
        int remaining = quota.consume(uid);
        DraftContext ctx = context(request);
        Map<String, Object> previous = schema.sanitize(request.draft() == null ? Map.of() : request.draft(), ctx);

        Optional<LlmResult<ModelOutput>> result =
                router.complete(schema.systemPrompt(ctx), user(ctx, previous, turns), parser::parse);
        if (result.isEmpty()) {
            count(schema, "degraded");
            return new FormDraftResponse(full(schema, previous), schema.understood(previous), schema.missing(previous),
                    null, List.of(), false, true, remaining);
        }
        ModelOutput out = result.get().value();
        Map<String, Object> merged = new LinkedHashMap<>(previous);
        merged.putAll(schema.sanitize(out.draft() == null ? Map.of() : out.draft(), ctx));
        merged = schema.sanitize(merged, ctx);
        List<String> missing = schema.missing(merged);
        boolean done = missing.isEmpty() && !out.needsConfirmation();
        String question = null;
        List<String> replies = List.of();
        if (!done) {
            question = truncate(out.question(), MAX_QUESTION);
            if (question == null) {
                question = missing.isEmpty() ? CONFIRM.get(ctx.locale()) : schema.fallbackQuestion(missing.get(0), ctx.locale());
            }
            replies = quickReplies(out.quickReplies());
        }
        count(schema, done ? "done" : "asking");
        registry.summary("assistant.form_draft.turns", "kind", schema.kind().tag()).record(userTurns);
        return new FormDraftResponse(full(schema, merged), schema.understood(merged), missing, question, replies,
                done, false, remaining);
    }

    /** La date du client (son fuseau) si elle est à un jour au plus de la date UTC du serveur. */
    DraftContext context(FormDraftRequest r) {
        LocalDate server = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate today = server;
        try {
            LocalDate client = LocalDate.parse(r.today());
            if (Math.abs(ChronoUnit.DAYS.between(server, client)) <= 1) today = client;
        } catch (RuntimeException ignored) {
            // Absente ou illisible : date du serveur.
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(r.timezone());
        } catch (RuntimeException e) {
            zone = ZoneOffset.UTC;
        }
        String locale = r.locale() != null && r.locale().strip().toLowerCase(Locale.ROOT).startsWith("en") ? "en" : "fr";
        return new DraftContext(today, zone, locale, DraftFields.currency(r.defaultCurrency()));
    }

    private String user(DraftContext ctx, Map<String, Object> previous, List<FormDraftTurn> turns) {
        StringBuilder sb = new StringBuilder();
        sb.append("<locale>").append(ctx.locale()).append("</locale>\n");
        String draft;
        try {
            draft = json.writeValueAsString(previous);
        } catch (JsonProcessingException e) {
            draft = "{}";
        }
        sb.append("<draft>").append(clean(draft)).append("</draft>\n<conversation>");
        for (FormDraftTurn t : turns) {
            String role = "assistant".equals(t.role()) ? "assistant" : "user";
            sb.append("\n<turn role=\"").append(role).append("\">").append(clean(t.text())).append("</turn>");
        }
        return sb.append("\n</conversation>").toString();
    }

    /** Toutes les clés du schéma, null pour les absentes : un contrat stable pour l'app. */
    private static Map<String, Object> full(FormDraftSchema schema, Map<String, Object> draft) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : schema.keys()) out.put(k, draft.get(k));
        return out;
    }

    private static List<String> quickReplies(List<String> raw) {
        if (raw == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String r : raw) {
            if (out.size() == MAX_QUICK_REPLIES) break;
            if (r == null) continue;
            String t = r.strip();
            if (!t.isEmpty() && t.length() <= MAX_QUICK_REPLY) out.add(t);
        }
        return out;
    }

    private void count(FormDraftSchema schema, String outcome) {
        registry.counter("assistant.form_draft.calls", "kind", schema.kind().tag(), "outcome", outcome).increment();
    }

    /** Retire < et > : un texte utilisateur ne peut ni fermer ni ouvrir une balise. */
    private static String clean(String text) {
        return text.replace("<", "").replace(">", "");
    }

    /** Tronque à max unités UTF-16 sans couper une paire de substitution ; null ou vide donne null. */
    private static String truncate(String text, int max) {
        if (text == null || text.isBlank()) return null;
        String t = text.strip();
        if (t.length() <= max) return t;
        int end = max;
        if (Character.isHighSurrogate(t.charAt(end - 1))) end--;
        return t.substring(0, end);
    }
}
