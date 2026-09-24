package com.yadony.api.common.i18n;

import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.Locale;

/**
 * Construit le {@link ResourceBundleMessageSource} des messages i18n, avec le
 * même réglage en production ({@code I18nConfig}) et en test ({@code TestMessages}).
 */
public final class MessageSourceFactory {

    public static final String BASENAME = "i18n/messages";

    private MessageSourceFactory() {
    }

    public static ResourceBundleMessageSource create() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename(BASENAME);
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        source.setDefaultLocale(Locale.FRENCH);
        source.setAlwaysUseMessageFormat(true);
        return source;
    }
}
