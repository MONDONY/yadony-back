package com.yadony.api.common;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Nom affichable par n'importe qui (page publique du QR colis) : prénom et
 * initiale du dernier mot. Un inconnu qui photographie un colis identifie
 * ainsi les personnes sans obtenir leur nom complet.
 *
 * <p>Le nom du destinataire est saisi librement par l'expéditeur : seuls les mots
 * faits de lettres sont gardés, pour qu'un numéro ou un e-mail tapé dans le champ
 * ne sorte jamais, même en partie.
 */
public final class PublicNameFormatter {

    public static final String UNKNOWN = "Non précisé";
    public static final int MAX_FIRST_NAME_LENGTH = 30;

    private static final Pattern NAME_WORD = Pattern.compile("\\p{L}[\\p{L}'’.-]*");

    private PublicNameFormatter() {
    }

    public static String shortName(String fullName) {
        if (fullName == null) {
            return UNKNOWN;
        }
        List<String> words = Arrays.stream(fullName.trim().split("\\s+"))
                .filter(w -> NAME_WORD.matcher(w).matches())
                .toList();
        if (words.isEmpty()) {
            return UNKNOWN;
        }
        String first = truncate(words.get(0));
        if (words.size() == 1) {
            return first;
        }
        String last = words.get(words.size() - 1);
        String initial = last.substring(0, last.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT);
        return first + " " + initial + ".";
    }

    private static String truncate(String word) {
        if (word.codePointCount(0, word.length()) <= MAX_FIRST_NAME_LENGTH) {
            return word;
        }
        return word.substring(0, word.offsetByCodePoints(0, MAX_FIRST_NAME_LENGTH));
    }
}
