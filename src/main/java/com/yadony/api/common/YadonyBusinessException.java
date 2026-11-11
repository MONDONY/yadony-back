package com.yadony.api.common;

import com.yadony.api.common.i18n.AppLanguage;
import com.yadony.api.common.i18n.Messages;
import com.yadony.api.common.i18n.MessageSourceFactory;
import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Refus métier rendu en ProblemDetail RFC 7807 par {@code GlobalExceptionHandler}.
 *
 * <p>Deux façons de la construire :
 * <ul>
 *   <li>{@link #localized} : le titre et le détail sont des clés i18n
 *       ({@code <clé>.title} et {@code <clé>.detail} dans {@code i18n/messages_*.properties}),
 *       résolues par le handler dans la langue de la requête ({@code Accept-Language}).
 *       C'est la forme à utiliser pour tout nouveau refus visible à l'écran.</li>
 *   <li>le constructeur à texte en dur (historique) : texte renvoyé tel quel, dans une
 *       seule langue. Il reste pour les packages pas encore migrés (voir
 *       {@code docs/i18n-messages-serveur.md}).</li>
 * </ul>
 * Pour une exception localisée, {@link #getMessage()} et {@link #getTitle()} renvoient le texte
 * français (logs, tests, appels internes) ; la langue de la requête n'est appliquée qu'à la réponse HTTP.
 */
public class YadonyBusinessException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final String title;
    private final Map<String, Object> properties;
    private final String messageKey;
    private final transient Object[] messageArgs;

    public YadonyBusinessException(HttpStatus status, String errorCode, String title, String detail) {
        this(status, errorCode, title, detail, Map.of());
    }

    public YadonyBusinessException(HttpStatus status, String errorCode, String title, String detail,
                                 Map<String, Object> properties) {
        this(status, errorCode, title, detail, properties, null, null);
    }

    private YadonyBusinessException(HttpStatus status, String errorCode, String title, String detail,
                                    Map<String, Object> properties, String messageKey, Object[] messageArgs) {
        super(detail);
        this.status = status;
        this.errorCode = errorCode;
        this.title = title;
        this.properties = properties;
        this.messageKey = messageKey;
        this.messageArgs = messageArgs;
    }

    /**
     * Refus dont le titre et le détail viennent des clés {@code messageKey + ".title"} et
     * {@code messageKey + ".detail"} ; {@code args} remplit les {@code {0}}, {@code {1}}... du détail.
     */
    public static YadonyBusinessException localized(HttpStatus status, String errorCode,
                                                    String messageKey, Object... args) {
        return localized(status, errorCode, messageKey, Map.of(), args);
    }

    public static YadonyBusinessException localized(HttpStatus status, String errorCode, String messageKey,
                                                    Map<String, Object> properties, Object... args) {
        Messages fr = new Messages(DefaultMessages.SOURCE, AppLanguage.FR);
        return new YadonyBusinessException(status, errorCode, fr.get(messageKey + ".title"),
                fr.get(messageKey + ".detail", args), properties, messageKey, args);
    }

    /** Même refus (clés i18n conservées) avec d'autres propriétés. */
    public YadonyBusinessException withProperties(Map<String, Object> newProperties) {
        return new YadonyBusinessException(status, errorCode, title, getMessage(), newProperties,
                messageKey, messageArgs);
    }

    public HttpStatus getStatus() { return status; }

    public String getErrorCode() { return errorCode; }

    public String getTitle() { return title; }

    public Map<String, Object> getProperties() { return properties; }

    public boolean isLocalized() { return messageKey != null; }

    /** Titre dans la langue demandée ; le titre d'origine si l'exception n'est pas localisée. */
    public String getTitle(Messages messages) {
        return isLocalized() ? messages.get(messageKey + ".title") : title;
    }

    /** Détail dans la langue demandée ; le détail d'origine si l'exception n'est pas localisée. */
    public String getDetail(Messages messages) {
        return isLocalized() ? messages.get(messageKey + ".detail", messageArgs) : getMessage();
    }

    private static final class DefaultMessages {
        static final org.springframework.context.MessageSource SOURCE = MessageSourceFactory.create();
    }
}
