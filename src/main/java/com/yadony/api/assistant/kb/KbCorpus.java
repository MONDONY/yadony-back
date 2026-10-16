package com.yadony.api.assistant.kb;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public record KbCorpus(Map<String, List<KbArticle>> byLocale) {

    public List<KbArticle> articles(String locale) {
        return byLocale.getOrDefault(locale, List.of());
    }

    /** Union des routes de toutes les fiches, toutes langues : la liste autorisée. */
    public Set<String> routePaths() {
        return byLocale.values().stream().flatMap(List::stream)
                .flatMap(a -> a.routes().stream()).map(KbRoute::path)
                .collect(Collectors.toUnmodifiableSet());
    }
}
