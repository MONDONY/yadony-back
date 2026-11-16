package com.yadony.api.assistant.adminhelp.dto;

import java.util.List;

public record AdminHelpArticleResponse(String id, String title, String category, String categoryLabel,
                                       String summary, String permission, boolean accessible,
                                       List<AdminHelpRoute> routes, String body,
                                       List<AdminHelpArticleSummary> related) {
}
