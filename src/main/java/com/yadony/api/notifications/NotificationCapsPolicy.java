package com.yadony.api.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Ce que fait le serveur quand un libellé dépasse {@link NotificationCaps}.
 *
 * <p>Le but est qu'un point d'émission trop long échoue <em>au test</em>, jamais
 * en production. Le mode se règle par {@code yadony.notifications.caps.mode} :
 * <ul>
 *   <li>{@code strict} : lève une exception, pour les suites de test une fois
 *       les libellés réécrits ;</li>
 *   <li>{@code warn} (défaut) : journalise et laisse passer, le temps que les
 *       ~66 points d'émission soient réécrits ;</li>
 *   <li>{@code off} : silencieux.</li>
 * </ul>
 * Le contrôle ne coupe jamais le texte lui-même : couper une phrase assemblée
 * au milieu d'un mot est justement ce que le contrat interdit. Seul le corps
 * d'une annonce est raccourci, et son texte complet survit dans {@code fullBody}.
 */
@Component
public class NotificationCapsPolicy {

    private static final Logger log = LoggerFactory.getLogger(NotificationCapsPolicy.class);

    public enum Mode { OFF, WARN, STRICT }

    private final Mode mode;

    public NotificationCapsPolicy(Mode mode) {
        this.mode = mode == null ? Mode.WARN : mode;
    }

    /** Lecture de la configuration, insensible à la casse ({@code warn}, {@code STRICT}...). */
    @Autowired
    public NotificationCapsPolicy(@Value("${yadony.notifications.caps.mode:warn}") String mode) {
        this(parse(mode));
    }

    private static Mode parse(String raw) {
        if (raw == null || raw.isBlank()) return Mode.WARN;
        try {
            return Mode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "yadony.notifications.caps.mode : valeur inconnue « " + raw + " », attendu off, warn ou strict", e);
        }
    }

    public Mode mode() {
        return mode;
    }

    /** Vérifie titre et corps pour le type donné ; voir la classe pour le comportement par mode. */
    public void check(String type, String title, String body) {
        if (mode == Mode.OFF) return;
        report(type, "title", title, NotificationCaps.TITLE_MAX);
        report(type, "body", body, NotificationCaps.BODY_MAX);
    }

    private void report(String type, String field, String value, int max) {
        if (value == null || value.length() <= max) return;
        String message = String.format(
                "Notification %s : %s de %d caractères dépasse le cap de %d (« %s »)",
                type, field, value.length(), max, value);
        if (mode == Mode.STRICT) {
            throw new IllegalArgumentException(message);
        }
        log.warn(message);
    }
}
