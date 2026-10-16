package com.yadony.api.assistant.kb;

import java.util.List;

public record KbArticle(String locale, String id, String title, List<String> keywords,
                        List<KbRoute> routes, String body) {
}
