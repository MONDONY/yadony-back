package com.yadony.api.common.i18n;

import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

/**
 * Résout les {@link Messages} d'un utilisateur ou de la requête courante.
 * Bean Spring déclaré dans {@code I18nConfig} (tâche A2), une fois
 * {@link UserLanguageLookup} implémenté par {@code auth.UserLanguageService}.
 */
public class MessagesResolver {

    private final MessageSource source;
    private final UserLanguageLookup users;

    public MessagesResolver(MessageSource source, UserLanguageLookup users) {
        this.source = source;
        this.users = users;
    }

    public Messages of(AppLanguage language) {
        return new Messages(source, language);
    }

    /**
     * Langue de l'utilisateur ; FR si null, inconnu ou supprimé (le port n'est
     * pas appelé pour un identifiant null).
     */
    public Messages forUser(UUID userId) {
        if (userId == null) {
            return of(AppLanguage.FR);
        }
        return of(users.languageOf(userId).orElse(AppLanguage.FR));
    }

    /**
     * Accept-Language de la requête courante, FR sans requête.
     */
    public AppLanguage requestLanguage() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            String header = servletAttributes.getRequest().getHeader(HttpHeaders.ACCEPT_LANGUAGE);
            return AppLanguage.fromAcceptLanguage(header);
        }
        return AppLanguage.FR;
    }

    public Messages forRequest() {
        return of(requestLanguage());
    }
}
