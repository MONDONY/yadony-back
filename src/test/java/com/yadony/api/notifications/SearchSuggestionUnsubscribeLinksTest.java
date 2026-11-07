package com.yadony.api.notifications;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SearchSuggestionUnsubscribeLinksTest {

    private final SearchSuggestionUnsubscribeTokens tokens = new SearchSuggestionUnsubscribeTokens(
            "cle-de-test", Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC));
    private final UUID userId = UUID.randomUUID();

    @Test
    void linkPointsToThePublicPageOfTheApi_withAVerifiableToken() {
        String url = new SearchSuggestionUnsubscribeLinks(tokens, "https://api.yadony.com/").urlFor(userId).orElseThrow();

        assertThat(url).startsWith("https://api.yadony.com/api/v1/public/notifications/search-suggestions/unsubscribe?t=");
        String token = java.net.URLDecoder.decode(url.substring(url.indexOf("?t=") + 3), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(tokens.verify(token)).contains(userId);
    }

    @Test
    void missingOrInvalidBase_givesNoLink() {
        assertThat(new SearchSuggestionUnsubscribeLinks(tokens, "").urlFor(userId)).isEmpty();
        assertThat(new SearchSuggestionUnsubscribeLinks(tokens, null).urlFor(userId)).isEmpty();
        assertThat(new SearchSuggestionUnsubscribeLinks(tokens, "api.yadony.com").urlFor(userId)).isEmpty();
        assertThat(new SearchSuggestionUnsubscribeLinks(tokens, "http://localhost:8080").urlFor(null)).isEmpty();
    }
}
