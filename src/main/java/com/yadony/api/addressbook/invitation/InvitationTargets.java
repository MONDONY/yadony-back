package com.yadony.api.addressbook.invitation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Normalisation, empreinte et masquage de la cible d'une invitation (numéro ou email).
 * Seules l'empreinte et la forme masquée quittent ce fichier : jamais la valeur en clair.
 */
final class InvitationTargets {

    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{6,14}$");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final int EMAIL_MAX = 254;
    private static final String DOTS = "••";

    /** Indicatifs à deux chiffres (zones 2 à 9 de l'UIT) ; les autres zones 2-9 en ont trois. */
    private static final Set<String> TWO_DIGIT_CODES = Set.of(
            "20", "27", "30", "31", "32", "33", "34", "36", "39", "40", "41", "43", "44", "45", "46",
            "47", "48", "49", "51", "52", "53", "54", "55", "56", "57", "58", "60", "61", "62", "63",
            "64", "65", "66", "81", "82", "84", "86", "90", "91", "92", "93", "94", "95", "98");

    private InvitationTargets() {}

    /** Numéro E.164 strict ({@code +} puis 7 à 15 chiffres, espaces tolérés), vide sinon. */
    static Optional<String> phone(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String compact = raw.replaceAll("[\\s.\\-()]", "");
        return E164.matcher(compact).matches() ? Optional.of(compact) : Optional.empty();
    }

    /** Email en minuscules sans espaces autour, vide s'il n'est pas valide. */
    static Optional<String> email(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return normalized.length() <= EMAIL_MAX && EMAIL.matcher(normalized).matches()
                ? Optional.of(normalized) : Optional.empty();
    }

    /** SHA-256 hexadécimal (64 caractères) de la cible déjà normalisée. */
    static String hash(String normalized) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    /** {@code +221771234512} → {@code +221 •• •• •• 12}. */
    static String maskPhone(String e164) {
        String digits = e164.substring(1);
        String code = digits.substring(0, callingCodeLength(digits));
        return "+" + code + " " + DOTS + " " + DOTS + " " + DOTS + " " + digits.substring(digits.length() - 2);
    }

    /** {@code awa.diallo@gmail.com} → {@code a••••@gmail.com}. */
    static String maskEmail(String email) {
        int at = email.indexOf('@');
        return email.charAt(0) + DOTS + DOTS + email.substring(at);
    }

    /**
     * Indicatif → pays ISO-2 : corridors Afrique et pays de la diaspora. Aucune table de ce
     * type n'existe ailleurs dans le back (pas de libphonenumber).
     */
    private static final Map<String, String> COUNTRY_BY_CALLING_CODE = Map.ofEntries(
            Map.entry("1", "US"), Map.entry("33", "FR"), Map.entry("32", "BE"), Map.entry("41", "CH"),
            Map.entry("39", "IT"), Map.entry("34", "ES"), Map.entry("49", "DE"), Map.entry("44", "GB"),
            Map.entry("31", "NL"), Map.entry("351", "PT"), Map.entry("352", "LU"),
            Map.entry("221", "SN"), Map.entry("225", "CI"), Map.entry("223", "ML"), Map.entry("237", "CM"),
            Map.entry("226", "BF"), Map.entry("224", "GN"), Map.entry("228", "TG"), Map.entry("229", "BJ"),
            Map.entry("227", "NE"), Map.entry("241", "GA"), Map.entry("242", "CG"), Map.entry("243", "CD"),
            Map.entry("222", "MR"), Map.entry("220", "GM"), Map.entry("245", "GW"), Map.entry("235", "TD"),
            Map.entry("236", "CF"), Map.entry("212", "MA"), Map.entry("213", "DZ"), Map.entry("216", "TN"));

    /**
     * Pays du carnet déduit de l'indicatif, vide s'il est inconnu. {@code +1} couvre les
     * États-Unis et le Canada : on retient US, faute de pouvoir distinguer sans l'indicatif régional.
     */
    static Optional<String> countryOf(String e164) {
        String digits = e164.substring(1);
        return Optional.ofNullable(COUNTRY_BY_CALLING_CODE.get(digits.substring(0, callingCodeLength(digits))));
    }

    static int callingCodeLength(String digits) {
        char zone = digits.charAt(0);
        if (zone == '1' || zone == '7') {
            return 1;
        }
        return TWO_DIGIT_CODES.contains(digits.substring(0, 2)) ? 2 : 3;
    }
}
