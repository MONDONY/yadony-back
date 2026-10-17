package com.yadony.api.assistant.kb;

import java.util.List;

/**
 * @param screens   écrans de l'app que la fiche explique (motif de route GoRouter, ex. {@code /bids/:bidId}) :
 *                  son corps s'affiche quand l'assistant est ouvert depuis l'un d'eux
 * @param questions questions proposées sous cette explication
 */
public record KbArticle(String locale, String id, String title, List<String> keywords,
                        List<KbRoute> routes, String body, List<String> screens, List<String> questions) {

    public KbArticle(String locale, String id, String title, List<String> keywords, List<KbRoute> routes,
                     String body) {
        this(locale, id, title, keywords, routes, body, List.of(), List.of());
    }
}
