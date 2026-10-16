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
    private static final Set<String> HEADER_KEYS = Set.of("id", "title", "keywords", "routes");
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
            parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
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
        String body = text.substring(end + 5).strip();
        return new KbArticle(locale, id, title, keywords, routes, body);
    }

    public static void validate(Map<String, List<KbArticle>> byLocale) {
        Map<String, Set<String>> idsByLocale = new LinkedHashMap<>();
        for (String locale : LOCALES) {
            Set<String> ids = new HashSet<>();
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
            }
            idsByLocale.put(locale, ids);
        }
        Set<String> fr = idsByLocale.get("fr");
        Set<String> en = idsByLocale.get("en");
        for (String id : fr) if (!en.contains(id)) throw invalid("en/" + id, "traduction manquante de " + id);
        for (String id : en) if (!fr.contains(id)) throw invalid("fr/" + id, "traduction manquante de " + id);
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
