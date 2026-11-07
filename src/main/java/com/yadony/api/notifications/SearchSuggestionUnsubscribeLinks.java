package com.yadony.api.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Adresse du lien de désinscription des e-mails de suggestion :
 * {@code {app.base-url}/api/v1/public/notifications/search-suggestions/unsubscribe?t=…}.
 *
 * <p>{@code app.base-url} est l'origine de l'API ({@code https://api.yadony.com} en prod,
 * {@code https://api-staging.yadony.com} en staging), comme pour les liens de suivi. Sans
 * origine http(s) valable, aucun lien : l'e-mail garde la ligne « Paramètres, Notifications ».
 */
@Component
public class SearchSuggestionUnsubscribeLinks {

    private static final Logger log = LoggerFactory.getLogger(SearchSuggestionUnsubscribeLinks.class);

    static final String PATH = "/api/v1/public/notifications/search-suggestions/unsubscribe";

    private final SearchSuggestionUnsubscribeTokens tokens;
    private final String base;

    public SearchSuggestionUnsubscribeLinks(SearchSuggestionUnsubscribeTokens tokens,
                                            @Value("${app.base-url:}") String appBaseUrl) {
        this.tokens = tokens;
        this.base = normalize(appBaseUrl);
    }

    /** Lien pour cet utilisateur, ou vide si l'origine de l'API n'est pas configurée. */
    public Optional<String> urlFor(UUID userId) {
        if (base == null || userId == null) {
            return Optional.empty();
        }
        return Optional.of(base + PATH + "?t=" + URLEncoder.encode(tokens.issue(userId), StandardCharsets.UTF_8));
    }

    private static String normalize(String appBaseUrl) {
        if (appBaseUrl == null || appBaseUrl.isBlank()
                || !(appBaseUrl.startsWith("https://") || appBaseUrl.startsWith("http://"))) {
            log.warn("app.base-url absente ou invalide : e-mails de suggestion sans lien de désinscription");
            return null;
        }
        String trimmed = appBaseUrl.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
