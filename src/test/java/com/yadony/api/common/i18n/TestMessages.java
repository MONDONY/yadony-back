package com.yadony.api.common.i18n;

import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * Aides de test pour l'i18n, réutilisées par toutes les tâches du chantier.
 */
public final class TestMessages {

    private TestMessages() {
    }

    public static MessageSource source() {
        return MessageSourceFactory.create();
    }

    public static Messages fr() {
        return new Messages(source(), AppLanguage.FR);
    }

    public static Messages en() {
        return new Messages(source(), AppLanguage.EN);
    }

    /**
     * Résolveur dont chaque utilisateur a la langue donnée ; requêtes sans en-tête → FR.
     */
    public static MessagesResolver resolver(AppLanguage everyUser) {
        return new MessagesResolver(source(), userId -> Optional.of(everyUser));
    }

    public static MessagesResolver resolver() {
        return resolver(AppLanguage.FR);
    }

    /**
     * Pose une requête courante portant cet Accept-Language ; à défaire par clearRequest() en @AfterEach.
     */
    public static void requestWithAcceptLanguage(String header) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.ACCEPT_LANGUAGE, header);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    public static void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }
}
