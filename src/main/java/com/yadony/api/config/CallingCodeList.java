package com.yadony.api.config;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Liste d'indicatifs téléphoniques internationaux stockée en texte (« 221,225,237 »), forme
 * des réglages de type {@link PlatformSettingType#CALLING_CODES}.
 *
 * <p>Un indicatif fait 1 à 4 chiffres et ne commence jamais par 0. À la saisie, le « + » et les
 * espaces sont tolérés puis retirés, les entrées vides ignorées et les doublons supprimés, dans
 * l'ordre de saisie.
 */
public final class CallingCodeList {

    /** Au-delà, la liste ne tiendrait plus dans la colonne et ne voudrait plus rien dire. */
    public static final int MAX_ENTRIES = 60;

    private static final Pattern CODE = Pattern.compile("[1-9]\\d{0,3}");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private CallingCodeList() {
    }

    /**
     * Forme normalisée d'une saisie admin, ou {@link IllegalArgumentException} dont le message
     * est affichable tel quel. Une saisie vide donne une liste vide (« aucun pays »).
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        Set<String> codes = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String code = clean(token);
            if (code.isEmpty()) {
                continue;
            }
            if (!CODE.matcher(code).matches()) {
                throw new IllegalArgumentException("Indicatif invalide : « " + token.trim()
                        + " ». Attendu : 1 à 4 chiffres, séparés par des virgules (ex. 221,225)");
            }
            codes.add(code);
        }
        if (codes.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Au plus " + MAX_ENTRIES + " indicatifs");
        }
        return String.join(",", codes);
    }

    /**
     * Lecture tolérante d'une valeur déjà stockée : les entrées illisibles sont ignorées plutôt
     * que de faire échouer un envoi.
     */
    public static List<String> parseLenient(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> codes = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String code = clean(token);
            if (CODE.matcher(code).matches()) {
                codes.add(code);
            }
        }
        return List.copyOf(codes);
    }

    /**
     * Indicatif de la liste par lequel commence ce numéro E.164 (le plus long s'il y en a
     * plusieurs), vide si aucun ou si le numéro n'est pas au format {@code +chiffres}.
     */
    public static Optional<String> match(List<String> codes, String phoneNumber) {
        if (phoneNumber == null || !phoneNumber.startsWith("+") || codes.isEmpty()) {
            return Optional.empty();
        }
        String digits = phoneNumber.substring(1);
        String best = null;
        for (String code : codes) {
            if (digits.startsWith(code) && (best == null || code.length() > best.length())) {
                best = code;
            }
        }
        return Optional.ofNullable(best);
    }

    private static String clean(String token) {
        String code = SPACES.matcher(token).replaceAll("");
        return code.startsWith("+") ? code.substring(1) : code;
    }
}
