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
            byLocale.put(locale, List.copyOf(articles));
        }
        validate(byLocale);
        return new KbCorpus(Map.copyOf(byLocale));
    }

    @SuppressWarnings("unchecked")
    public static KbArticle parse(String locale, String filename, String raw) {
        String where = locale + "/" + filename;
        String text = raw.replace("\r\n", "\n");
        if (!text.startsWith("---\n")) {
            throw invalid(where, "en-tête --- manquant");
        }
        int end = text.indexOf("\n---\n", 4);
        if (end < 0) {
            throw invalid(where, "fin d'en-tête --- manquante");
        }
        Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(text.substring(4, end));
        if (!(parsed instanceof Map<?, ?> header)) {
            throw invalid(where, "en-tête illisible");
        }
        String id = string(header.get("id"));
        String title = string(header.get("title"));
        List<String> keywords = new ArrayList<>();
        Object kw = header.get("keywords");
        if (kw instanceof String s) {
            for (String k : s.split(",")) {
                if (!k.isBlank()) keywords.add(k.trim());
            }
        }
        List<KbRoute> routes = new ArrayList<>();
        Object rs = header.get("routes");
        if (rs instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) throw invalid(where, "route illisible");
                routes.add(new KbRoute(string(m.get("path")), string(m.get("label"))));
            }
        }
        String body = text.substring(end + 5).strip();
        return new KbArticle(locale, id, title, List.copyOf(keywords), List.copyOf(routes), body);
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

    private static String string(Object o) {
        return o == null ? null : o.toString().trim();
    }

    private static IllegalStateException invalid(String where, String reason) {
        return new IllegalStateException("Fiche assistant invalide (" + where + ") : " + reason);
    }
}
