package com.yadony.api.common;

import com.yadony.api.common.i18n.AppLanguage;
import com.yadony.api.common.i18n.MessageSourceFactory;
import com.yadony.api.common.i18n.Messages;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@link ResponseStatusException} dont la raison est une clé i18n ({@code problem.*}, une seule
 * valeur par langue). {@code GlobalExceptionHandler} la résout selon l'{@code Accept-Language}
 * de la requête ; {@link #getReason()} et {@code getMessage()} restent en français (logs, tests).
 * Même type que l'exception historique : les appelants et tests qui attendent un
 * {@code ResponseStatusException} ne changent pas.
 */
public class LocalizedResponseStatusException extends ResponseStatusException {

    private final String messageKey;
    private final transient Object[] messageArgs;

    public LocalizedResponseStatusException(HttpStatusCode status, String messageKey, Object... args) {
        super(status, new Messages(DefaultMessages.SOURCE, AppLanguage.FR).get(messageKey, args));
        this.messageKey = messageKey;
        this.messageArgs = args;
    }

    /** Raison dans la langue demandée. */
    public String getReason(Messages messages) {
        return messages.get(messageKey, messageArgs);
    }

    private static final class DefaultMessages {
        static final MessageSource SOURCE = MessageSourceFactory.create();
    }
}
