package com.yadony.api.assistant.adminhelp;

import com.yadony.api.admin.account.AdminPermission;
import com.yadony.api.assistant.kb.KbRoute;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lit et valide les fiches du centre d'aide admin ({@code assistant/admin-help/*.md}). Comme pour les fiches de
 * l'app, une fiche invalide fait échouer le démarrage : jamais de fiche ignorée sans bruit.
 */
public final class AdminHelpLoader {

    /** Rubriques, dans l'ordre d'affichage, avec leur libellé. */
    public static final Map<String, String> CATEGORIES;

    static {
        Map<String, String> c = new LinkedHashMap<>();
        c.put("premiers-pas", "Premiers pas");
        c.put("comptes", "Comptes");
        c.put("argent", "Argent");
        c.put("activite", "Activité");
        c.put("support", "Support et communauté");
        c.put("systeme", "Système");
        CATEGORIES = java.util.Collections.unmodifiableMap(c);
    }

    static final int MAX_BODY = 6000;
    static final int MAX_TITLE = 120;
    static final int MAX_SUMMARY = 200;
    static final int MAX_ROUTES = 4;
    static final int MAX_LABEL = 40;
    private static final Set<String> HEADER_KEYS =
            Set.of("id", "title", "category", "order", "summary", "permission", "keywords", "routes");
    private static final Pattern ID = Pattern.compile("[a-z0-9-]{3,64}");
    /** Chemin interne, avec une requête facultative : ni fragment, ni schéma, ni « // ». */
    private static final Pattern ROUTE = Pattern.compile("/(?!/)[A-Za-z0-9_\\-/]*(\\?[A-Za-z0-9_\\-=&.%]+)?");
    /** Liens Markdown du corps : ils doivent viser une page connue ou une autre fiche. */
    private static final Pattern LINK = Pattern.compile("\\[[^\\]\\n]*]\\(([^)\\s]*)\\)");
    private static final Pattern ARTICLE_LINK = Pattern.compile("/aide/([a-z0-9-]{3,64})");
    private static final String EM_DASH = "—";
    private static final String EN_DASH = "–";

    private AdminHelpLoader() {
    }

    public static List<AdminHelpArticle> load(ResourcePatternResolver resolver, String root) {
        List<AdminHelpArticle> articles = new ArrayList<>();
        try {
            Resource[] resources = resolver.getResources(root + "/*.md");
            Arrays.sort(resources, (x, y) -> String.valueOf(x.getFilename()).compareTo(String.valueOf(y.getFilename())));
            for (Resource r : resources) {
                articles.add(parse(r.getFilename(), r.getContentAsString(StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Fiches du centre d'aide admin illisibles", e);
        }
        if (articles.isEmpty()) {
            throw new IllegalStateException("Fiches du centre d'aide admin introuvables dans " + root);
        }
        validate(articles);
        return List.copyOf(articles);
    }

    public static AdminHelpArticle parse(String filename, String raw) {
        String where = String.valueOf(filename);
        String text = raw.replace("\r\n", "\n");
        if (!text.startsWith("---\n")) throw invalid(where, "en-tête --- manquant");
        int end = text.indexOf("\n---\n", 3);
        if (end < 0) throw invalid(where, "fin d'en-tête --- manquante");
        String yaml = end > 3 ? text.substring(4, end) : "";
        Object parsed;
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            parsed = new Yaml(new SafeConstructor(options)).load(yaml);
        } catch (RuntimeException e) {
            throw invalid(where, "en-tête illisible");
        }
        if (!(parsed instanceof Map<?, ?> header)) throw invalid(where, "en-tête illisible");
        for (Object key : header.keySet()) {
            if (!HEADER_KEYS.contains(String.valueOf(key))) throw invalid(where, "clé d'en-tête inconnue : " + key);
        }
        Object order = header.get("order");
        if (order != null && !(order instanceof Integer)) throw invalid(where, "order doit être un entier");
        AdminHelpArticle article = new AdminHelpArticle(
                string(header.get("id")), string(header.get("title")), string(header.get("category")),
                order == null ? 100 : (Integer) order, string(header.get("summary")), string(header.get("permission")),
                keywords(where, header.get("keywords")), routes(where, header.get("routes")),
                text.substring(end + 5).strip());
        if (filename != null && !filename.equals(article.id() + ".md")) {
            throw invalid(where, "le nom du fichier doit être l'id suivi de .md");
        }
        return article;
    }

    public static void validate(List<AdminHelpArticle> articles) {
        Set<String> ids = new HashSet<>();
        for (AdminHelpArticle a : articles) {
            String where = String.valueOf(a.id());
            if (a.id() == null || !ID.matcher(a.id()).matches()) throw invalid(where, "id invalide");
            if (!ids.add(a.id())) throw invalid(where, "id en double");
        }
        for (AdminHelpArticle a : articles) {
            String where = a.id();
            if (blank(a.title()) || a.title().length() > MAX_TITLE) throw invalid(where, "title vide ou trop long");
            if (!CATEGORIES.containsKey(a.category())) throw invalid(where, "category inconnue : " + a.category());
            if (blank(a.summary()) || a.summary().length() > MAX_SUMMARY) throw invalid(where, "summary vide ou trop long");
            if (blank(a.body()) || a.body().length() > MAX_BODY) throw invalid(where, "corps vide ou trop long");
            if (a.permission() != null) {
                try {
                    AdminPermission.valueOf(a.permission());
                } catch (IllegalArgumentException e) {
                    throw invalid(where, "permission inconnue : " + a.permission());
                }
            }
            if (a.routes().size() > MAX_ROUTES) throw invalid(where, "plus de " + MAX_ROUTES + " routes");
            for (KbRoute r : a.routes()) {
                if (r.path() == null || !ROUTE.matcher(r.path()).matches()) throw invalid(where, "route invalide : " + r.path());
                if (!AdminHelpPages.known(r.path())) throw invalid(where, "route vers une page inconnue : " + r.path());
                if (blank(r.label()) || r.label().length() > MAX_LABEL) throw invalid(where, "label vide ou trop long");
            }
            if (List.of(a.title(), a.summary(), a.body(), String.join(",", a.keywords()),
                    String.join(",", a.routes().stream().map(KbRoute::label).toList()))
                    .stream().anyMatch(s -> s.contains(EM_DASH) || s.contains(EN_DASH))) {
                throw invalid(where, "tiret cadratin ou demi-cadratin dans le texte");
            }
            if (a.body().contains("<")) throw invalid(where, "HTML interdit dans le corps");
            Matcher links = LINK.matcher(a.body());
            while (links.find()) {
                String target = links.group(1);
                Matcher article = ARTICLE_LINK.matcher(target);
                if (article.matches()) {
                    if (!ids.contains(article.group(1))) throw invalid(where, "lien vers une fiche inconnue : " + target);
                } else if (!ROUTE.matcher(target).matches() || !AdminHelpPages.known(target)) {
                    throw invalid(where, "lien invalide : " + target);
                }
            }
        }
    }

    private static List<String> keywords(String where, Object raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        if (raw instanceof String s) {
            for (String k : s.split(",")) if (!k.isBlank()) out.add(k.trim());
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

    private static List<KbRoute> routes(String where, Object raw) {
        List<KbRoute> out = new ArrayList<>();
        if (raw == null) return out;
        if (!(raw instanceof List<?> list)) throw invalid(where, "routes doit être une liste");
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) throw invalid(where, "route illisible");
            out.add(new KbRoute(string(m.get("path")), string(m.get("label"))));
        }
        return List.copyOf(out);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String string(Object o) {
        return o == null ? null : o.toString().trim();
    }

    private static IllegalStateException invalid(String where, String reason) {
        return new IllegalStateException("Fiche du centre d'aide admin invalide (" + where + ") : " + reason);
    }
}
