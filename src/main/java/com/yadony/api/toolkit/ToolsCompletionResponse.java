package com.yadony.api.toolkit;

import java.util.List;

/** Réponse de {@code GET /users/me/tools-completion}. */
public record ToolsCompletionResponse(int total, int ready, List<ToolStatus> tools) {

    /** Un outil : {@code ready} vaut {@code count > 0}, jamais autre chose. */
    public record ToolStatus(String key, long count, boolean ready) {
        static ToolStatus of(ToolKey key, long count) {
            return new ToolStatus(key.apiKey(), count, count > 0);
        }
    }
}
