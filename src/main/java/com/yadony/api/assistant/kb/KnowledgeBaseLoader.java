package com.yadony.api.assistant.kb;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lit et valide les fiches de l'assistant. Une fiche invalide fait échouer le démarrage :
 * jamais d'oubli silencieux (spec §3.1).
 */
public final class KnowledgeBaseLoader {

    public static final List<String> LOCALES = List.of("fr", "en");
    private static final Set<String> HEADER_KEYS = Set.of("id", "title", "keywords", "routes", "screens", "questions");
    /** Motif de route GoRouter : segments fixes ou paramètres « :nom », sans requête ni fragment. */
    private static final Pattern SCREEN = Pattern.compile("/|(/(:?[A-Za-z0-9_-]+))+");
    /** L'explication d'un écran s'affiche telle quelle dans une bulle : courte. */
    static final int MAX_SCREEN_BODY = 600;
    static final int MAX_QUESTIONS = 3;
    static final int MAX_QUESTION_LENGTH = 80;
    private static final Pattern ID = Pattern.compile("[a-z0-9-]{3,64}");
    private static final String EM_DASH = "—";

    private KnowledgeBaseLoader() {
    }

    public static KbCorpus load(ResourcePatternResolver resolver, String root) {
        Map<String, List<KbArticle>> byLocale = new LinkedHashMap<>();
        for (String locale : LOCALES) {
            List<KbArticle> articles = new ArrayList<>();
            try {
                Resource[] resources = resolver.getResources(root + "/" + locale + "/*.md");
                Arrays.sort(resources, (x, y) -> String.valueOf(x.getFilename()).compareTo(String.valueOf(y.getFilename())));
                for (Resource r : resources) {
                    String raw = r.getContentAsString(StandardCharsets.UTF_8);
                    articles.add(parse(locale, r.getFilename(), raw));
                }
            } catch (IOException e) {
                throw new IllegalStateException("Fiches assistant illisibles (" + locale + ")", e);
            }
            if (articles.isEmpty()) {
                throw new IllegalStateException("Fiches assistant introuvables (" + locale + ") dans " + root);
            }
            byLocale.put(locale, List.copyOf(articles));
        }
        validate(byLocale);
        return new KbCorpus(Map.copyOf(byLocale));
    }

    public static KbArticle parse(String locale, String filename, String raw) {
        String where = locale + "/" + filename;
        String text = raw.replace("\r\n", "\n");
        if (!text.startsWith("---\n")) {
            throw invalid(where, "en-tête --- manquant");
        }
        // La recherche démarre à 3 : un en-tête vide (---\n---\n) a sa fin à l'index 3.
        int end = text.indexOf("\n---\n", 3);
        if (end < 0) {
            throw invalid(where, "fin d'en-tête --- manquante");
        }
        String yaml = end > 3 ? text.substring(4, end) : "";
        Object parsed;
        try {
            LoaderOptions options = new LoaderOptions();
            // Deux « routes: » dans un en-tête : SnakeYAML garderait la dernière sans rien dire.
            options.setAllowDuplicateKeys(false);
            parsed = new Yaml(new SafeConstructor(options)).load(yaml);
        } catch (RuntimeException e) {
            throw invalid(where, "en-tête illisible");
        }
        if (!(parsed instanceof Map<?, ?> header)) {
            throw invalid(where, "en-tête illisible");
        }
        for (Object key : header.keySet()) {
            if (!HEADER_KEYS.contains(String.valueOf(key))) {
                throw invalid(where, "clé d'en-tête inconnue : " + key);
            }
        }
        String id = string(header.get("id"));
        String title = string(header.get("title"));
        List<String> keywords = parseKeywords(where, header.get("keywords"));
        List<KbRoute> routes = parseRoutes(where, header.get("routes"));
        List<String> screens = parseStrings(where, "screens", header.get("screens"));
        List<String> questions = parseStrings(where, "questions", header.get("questions"));
        String body = text.substring(end + 5).strip();
        return new KbArticle(locale, id, title, keywords, routes, body, screens, questions);
    }

    public static void validate(Map<String, List<KbArticle>> byLocale) {
        Map<String, Set<String>> idsByLocale = new LinkedHashMap<>();
        for (String locale : LOCALES) {
            Set<String> ids = new HashSet<>();
            Set<String> screens = new HashSet<>();
            for (KbArticle a : byLocale.getOrDefault(locale, List.of())) {
                String where = locale + "/" + a.id();
                if (a.id() == null || !ID.matcher(a.id()).matches()) throw invalid(where, "id invalide");
                if (!ids.add(a.id())) throw invalid(where, "id en double");
                if (a.title() == null || a.title().isBlank() || a.title().length() > 120) {
                    throw invalid(where, "title vide ou trop long");
                }
                if (a.body().isBlank() || a.body().length() > 2000) throw invalid(where, "corps vide ou trop long");
                if (a.routes().size() > 3) throw invalid(where, "plus de 3 routes");
                for (KbRoute r : a.routes()) {
                    String p = r.path();
                    if (p == null || !p.startsWith("/") || p.contains(":") || p.contains("?") || p.contains("#")) {
                        throw invalid(where, "route invalide : " + p);
                    }
                    if (r.label() == null || r.label().isBlank() || r.label().length() > 40) {
                        throw invalid(where, "label vide ou trop long");
                    }
                    if (r.label().contains(EM_DASH)) throw invalid(where, "tiret cadratin dans un label");
                }
                for (String k : a.keywords()) {
                    if (k.contains(EM_DASH)) throw invalid(where, "tiret cadratin dans les mots-clés");
                }
                if (a.title().contains(EM_DASH) || a.body().contains(EM_DASH)) {
                    throw invalid(where, "tiret cadratin dans le texte");
                }
                validateScreen(where, a, screens);
            }
            idsByLocale.put(locale, ids);
        }
        // Une fiche d'écran explique les mêmes écrans dans les deux langues.
        Map<String, List<String>> frScreens = new HashMap<>();
        for (KbArticle a : byLocale.getOrDefault("fr", List.of())) frScreens.put(a.id(), a.screens());
        for (KbArticle a : byLocale.getOrDefault("en", List.of())) {
            List<String> fr = frScreens.get(a.id());
            if (fr != null && !Set.copyOf(fr).equals(Set.copyOf(a.screens()))) {
                throw invalid("en/" + a.id(), "écrans différents de la version française");
            }
        }
        Set<String> fr = idsByLocale.get("fr");
        Set<String> en = idsByLocale.get("en");
        for (String id : fr) if (!en.contains(id)) throw invalid("en/" + id, "traduction manquante de " + id);
        for (String id : en) if (!fr.contains(id)) throw invalid("fr/" + id, "traduction manquante de " + id);
    }

    private static void validateScreen(String where, KbArticle a, Set<String> seen) {
        if (a.screens().isEmpty()) {
            if (!a.questions().isEmpty()) throw invalid(where, "questions sans écran");
            return;
        }
        if (a.screens().size() > 5) throw invalid(where, "plus de 5 écrans");
        for (String s : a.screens()) {
            if (!SCREEN.matcher(s).matches() || s.length() > 120) throw invalid(where, "écran invalide : " + s);
            if (!seen.add(s)) throw invalid(where, "écran déjà expliqué par une autre fiche : " + s);
        }
        if (a.body().length() > MAX_SCREEN_BODY) throw invalid(where, "explication d'écran trop longue");
        if (a.questions().size() > MAX_QUESTIONS) throw invalid(where, "plus de " + MAX_QUESTIONS + " questions");
        for (String q : a.questions()) {
            if (q.isBlank() || q.length() > MAX_QUESTION_LENGTH) throw invalid(where, "question vide ou trop longue");
            if (q.contains(EM_DASH)) throw invalid(where, "tiret cadratin dans une question");
        }
    }

    /** Liste YAML de chaînes, ou absente. */
    private static List<String> parseStrings(String where, String key, Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) throw invalid(where, key + " doit être une liste");
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof String s) || s.isBlank()) throw invalid(where, key + " illisible");
            out.add(s.trim());
        }
        return List.copyOf(out);
    }

    /** Mots-clés : chaîne séparée par des virgules, ou liste YAML de chaînes. */
    private static List<String> parseKeywords(String where, Object raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        if (raw instanceof String s) {
            for (String k : s.split(",")) {
                if (!k.isBlank()) out.add(k.trim());
            }
        } else if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof String k)) throw invalid(where, "mots-clés illisibles");
                if (!k.isBlank()) out.add(k.trim());
            }
        } else {
            throw invalid(where, "mots-clés illisibles");
        }
        return List.copyOf(out);
    }

    private static List<KbRoute> parseRoutes(String where, Object raw) {
        List<KbRoute> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        if (!(raw instanceof List<?> list)) throw invalid(where, "routes doit être une liste");
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) throw invalid(where, "route illisible");
            out.add(new KbRoute(string(m.get("path")), string(m.get("label"))));
        }
        return List.copyOf(out);
    }

    private static String string(Object o) {
        return o == null ? null : o.toString().trim();
    }

    private static IllegalStateException invalid(String where, String reason) {
        return new IllegalStateException("Fiche assistant invalide (" + where + ") : " + reason);
    }
}
