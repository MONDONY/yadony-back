package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Locale;

/** Règles de contenu d'un bid vis-à-vis de l'annonce (paquet matching). */
final class BidContentRules {

    private BidContentRules() {
    }

    /** Lève 422 si une catégorie déclarée figure dans les types refusés de l'annonce. */
    static void assertNotRefused(AnnouncementEntity announcement, String contentCategory) {
        if (contentCategory == null || contentCategory.isBlank()) {
            return;
        }
        List<String> refused = announcement.getRefusedTypes();
        if (refused == null || refused.isEmpty()) {
            return;
        }
        List<String> refusedLower = refused.stream()
                .map(s -> s.toLowerCase(Locale.ROOT).strip())
                .toList();
        for (String raw : contentCategory.split(",")) {
            String item = raw.toLowerCase(Locale.ROOT).strip();
            if (!item.isEmpty() && refusedLower.contains(item)) {
                throw YadonyBusinessException.localized(HttpStatus.UNPROCESSABLE_ENTITY, "content-type-refused", "problem.content-type-refused", raw.strip());
            }
        }
    }
}
