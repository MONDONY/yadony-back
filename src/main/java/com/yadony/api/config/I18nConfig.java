package com.yadony.api.config;

import com.yadony.api.common.i18n.MessageSourceFactory;
import com.yadony.api.common.i18n.MessagesResolver;
import com.yadony.api.common.i18n.UserLanguageLookup;
import com.yadony.api.common.i18n.YadonyLocaleResolver;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;

/**
 * Socle i18n : source des messages et résolution de la locale à partir de
 * l'en-tête {@code Accept-Language}.
 */
@Configuration
public class I18nConfig {

    @Bean
    public MessageSource messageSource() {
        return MessageSourceFactory.create();
    }

    @Bean
    public LocaleResolver localeResolver() {
        return new YadonyLocaleResolver();
    }

    @Bean
    public MessagesResolver messagesResolver(MessageSource messageSource, UserLanguageLookup users) {
        return new MessagesResolver(messageSource, users);
    }
}
