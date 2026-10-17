package com.yadony.api.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Réglages de {@link RateLimitRule} : {@code yadony.rate-limit.enabled} et, par règle,
 * {@code yadony.rate-limit.rules.<nom>.limit} / {@code .window}. Une règle absente de
 * la configuration garde ses valeurs par défaut.
 */
@Component
@ConfigurationProperties(prefix = "yadony.rate-limit")
public class RateLimitProperties {

    /** Désactivé dans les tests d'intégration, qui enchaînent les appels d'un même compte. */
    private boolean enabled = true;

    private Map<String, Override> rules = new HashMap<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Map<String, Override> getRules() { return rules; }
    public void setRules(Map<String, Override> rules) { this.rules = rules; }

    int limitOf(RateLimitRule rule) {
        Override o = rules.get(rule.propertyName());
        return o != null && o.getLimit() != null ? o.getLimit() : rule.defaultLimit();
    }

    Duration windowOf(RateLimitRule rule) {
        Override o = rules.get(rule.propertyName());
        return o != null && o.getWindow() != null ? o.getWindow() : rule.defaultWindow();
    }

    public static class Override {
        private Integer limit;
        private Duration window;

        public Integer getLimit() { return limit; }
        public void setLimit(Integer limit) { this.limit = limit; }
        public Duration getWindow() { return window; }
        public void setWindow(Duration window) { this.window = window; }
    }
}
