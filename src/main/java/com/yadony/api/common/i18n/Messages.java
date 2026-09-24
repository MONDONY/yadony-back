package com.yadony.api.common.i18n;

import org.springframework.context.MessageSource;

import java.util.Locale;

/**
 * Textes d'une langue. Valeur immuable, sans état Spring.
 */
public final class Messages {

    private final MessageSource source;
    private final AppLanguage language;

    public Messages(MessageSource source, AppLanguage language) {
        this.source = source;
        this.language = language;
    }

    public AppLanguage language() {
        return language;
    }

    public Locale locale() {
        return language.locale();
    }

    /**
     * Les arguments Number sont passés en String (pas de groupement « 1 250 »).
     */
    public String get(String key, Object... args) {
        return source.getMessage(key, convert(args), language.locale());
    }

    /**
     * Choisit key + ".one" ou key + ".other" selon AppLanguage.isSingular(count).
     */
    public String plural(String key, long count, Object... args) {
        String suffix = language.isSingular(count) ? ".one" : ".other";
        return get(key + suffix, args);
    }

    private Object[] convert(Object[] args) {
        if (args == null) {
            return null;
        }
        Object[] converted = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            converted[i] = (arg instanceof Number) ? String.valueOf(arg) : arg;
        }
        return converted;
    }
}
