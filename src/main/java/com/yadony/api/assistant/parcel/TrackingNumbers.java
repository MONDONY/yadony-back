package com.yadony.api.assistant.parcel;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Numéro de suivi DON-XXXXXXXX dans un texte libre : « don-7k3m9qxa », « DON 7K3M9QXA » et
 * « DON7K3M9QXA » sont le même numéro. Il ne part jamais vers le fournisseur d'IA.
 */
public final class TrackingNumbers {

    private static final Pattern NUMBER = Pattern.compile("(?i)\\bDON\\s*-?\\s*([A-Z0-9]{8})\\b");
    static final String PLACEHOLDER = "[tracking number]";

    private TrackingNumbers() {
    }

    /** Premier numéro du texte, au format enregistré en base (majuscules, tiret). */
    public static Optional<String> find(String text) {
        if (text == null) return Optional.empty();
        Matcher m = NUMBER.matcher(text);
        return m.find() ? Optional.of("DON-" + m.group(1).toUpperCase(Locale.ROOT)) : Optional.empty();
    }

    /** Le texte envoyé au modèle : chaque numéro devient un repère neutre. */
    public static String mask(String text) {
        return text == null ? null : NUMBER.matcher(text).replaceAll(PLACEHOLDER);
    }

    /** Le texte cherché dans les fiches : sans le numéro, qui n'y figure pas. */
    public static String strip(String text) {
        return text == null ? "" : NUMBER.matcher(text).replaceAll(" ").strip();
    }
}
