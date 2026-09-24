package com.yadony.api.common.i18n;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Langue prise en charge par l'application. Deux valeurs seulement (fr, en),
 * conformément à la colonne {@code users.preferred_language}.
 */
public enum AppLanguage {

    FR("fr", Locale.FRENCH),
    EN("en", Locale.ENGLISH);

    private final String code;
    private final Locale locale;

    AppLanguage(String code, Locale locale) {
        this.code = code;
        this.locale = locale;
    }

    public String code() {
        return code;
    }

    public Locale locale() {
        return locale;
    }

    /**
     * Code « fr »/« en » (casse et blancs ignorés), vide sinon.
     */
    public static Optional<AppLanguage> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String trimmed = code.trim();
        for (AppLanguage language : values()) {
            if (language.code.equalsIgnoreCase(trimmed)) {
                return Optional.of(language);
            }
        }
        return Optional.empty();
    }

    /**
     * Première langue prise en charge selon les poids de l'en-tête ; FR si aucune, vide ou mal formé.
     */
    public static AppLanguage fromAcceptLanguage(String header) {
        if (header == null || header.isBlank()) {
            return FR;
        }
        List<Locale.LanguageRange> ranges;
        try {
            ranges = Locale.LanguageRange.parse(header);
        } catch (IllegalArgumentException e) {
            return FR;
        }
        for (Locale.LanguageRange range : ranges) {
            if (range.getWeight() == 0) {
                continue;
            }
            String primary = range.getRange().split("-")[0];
            if ("en".equals(primary)) {
                return EN;
            }
            if ("fr".equals(primary) || "*".equals(primary)) {
                return FR;
            }
        }
        return FR;
    }

    /**
     * Forme du singulier : FR si n <= 1, EN si n == 1.
     */
    public boolean isSingular(long n) {
        return switch (this) {
            case FR -> n <= 1;
            case EN -> n == 1;
        };
    }
}
