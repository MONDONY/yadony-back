package com.yadony.api.common;

import com.yadony.api.common.i18n.AppLanguage;
import com.yadony.api.common.i18n.MessageSourceFactory;
import com.yadony.api.common.i18n.Messages;

public class YadonyNotFoundException extends RuntimeException {

    private final String messageKey;
    private final transient Object[] messageArgs;

    public YadonyNotFoundException(String message) {
        super(message);
        this.messageKey = null;
        this.messageArgs = null;
    }

    public YadonyNotFoundException(String entityName, Object id) {
        super(entityName + " not found with id: " + id);
        this.messageKey = null;
        this.messageArgs = null;
    }

    private YadonyNotFoundException(String message, String messageKey, Object[] messageArgs) {
        super(message);
        this.messageKey = messageKey;
        this.messageArgs = messageArgs;
    }

    /**
     * Introuvable dont le détail est la valeur de la clé i18n {@code messageKey} (une seule valeur par
     * langue), résolue selon l'{@code Accept-Language} ; {@code getMessage()} reste en français.
     */
    public static YadonyNotFoundException localized(String messageKey, Object... args) {
        Messages fr = new Messages(DefaultMessages.SOURCE, AppLanguage.FR);
        return new YadonyNotFoundException(fr.get(messageKey, args), messageKey, args);
    }

    /** Détail dans la langue demandée ; le message d'origine si l'exception n'est pas localisée. */
    public String getDetail(Messages messages) {
        return messageKey == null ? getMessage() : messages.get(messageKey, messageArgs);
    }

    private static final class DefaultMessages {
        static final org.springframework.context.MessageSource SOURCE = MessageSourceFactory.create();
    }
}
