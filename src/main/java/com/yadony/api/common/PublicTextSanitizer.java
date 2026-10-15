package com.yadony.api.common;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Texte libre affiché à n'importe qui (page publique du QR colis) : masque les
 * numéros de téléphone et les adresses e-mail qu'un utilisateur aurait tapés
 * dedans. Un numéro = au moins {@value #MIN_PHONE_DIGITS} chiffres, séparés ou
 * non par des espaces, points, tirets ou parenthèses ; les petits nombres
 * (« 2 boubous », « 12.5 kg », « lot 2024 ») restent lisibles.
 */
public final class PublicTextSanitizer {

    public static final String MASK = "•••";
    static final int MIN_PHONE_DIGITS = 6;

    private static final Pattern EMAIL =
            Pattern.compile("[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)+");
    private static final Pattern DIGIT_RUN =
            Pattern.compile("\\+?\\(?\\d[\\d\\s.()-]*\\d");

    private PublicTextSanitizer() {
    }

    public static String maskContacts(String text) {
        if (text == null) {
            return "";
        }
        String withoutEmails = EMAIL.matcher(text).replaceAll(MASK);
        Matcher m = DIGIT_RUN.matcher(withoutEmails);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String run = m.group();
            long digits = run.chars().filter(Character::isDigit).count();
            m.appendReplacement(out, Matcher.quoteReplacement(digits >= MIN_PHONE_DIGITS ? MASK : run));
        }
        m.appendTail(out);
        return out.toString();
    }
}
