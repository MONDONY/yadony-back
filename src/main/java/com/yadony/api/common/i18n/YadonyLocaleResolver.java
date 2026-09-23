package com.yadony.api.common.i18n;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.LocaleResolver;

import java.util.Locale;

/**
 * {@link LocaleResolver} du {@code DispatcherServlet} : la langue suit
 * toujours l'en-tête {@code Accept-Language} de la requête, jamais une
 * session ou un cookie.
 */
public class YadonyLocaleResolver implements LocaleResolver {

    @Override
    public Locale resolveLocale(HttpServletRequest request) {
        return AppLanguage.fromAcceptLanguage(request.getHeader(HttpHeaders.ACCEPT_LANGUAGE)).locale();
    }

    @Override
    public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
        throw new UnsupportedOperationException("La langue suit l'en-tête Accept-Language, elle ne se fixe pas");
    }
}
