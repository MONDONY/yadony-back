package com.yadony.api.matching.reception;

import java.util.Optional;
import java.util.regex.Pattern;

/** Normalisation du numéro de destinataire, saisi librement par l'expéditeur. */
final class ReceptionPhones {

    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-.()]");
    private static final Pattern E164 = Pattern.compile("\\+\\d{8,15}");
    private static final Pattern NON_DIGITS = Pattern.compile("\\D");

    private ReceptionPhones() {}

    /**
     * Numéro E.164 ({@code +} puis 8 à 15 chiffres) après retrait des espaces, tirets,
     * points et parenthèses, « 00 » initial lu comme « + ». Vide si le numéro n'est pas
     * international : on ne devine jamais un indicatif.
     */
    static Optional<String> toE164(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String compact = SEPARATORS.matcher(raw).replaceAll("");
        if (compact.startsWith("00")) {
            compact = "+" + compact.substring(2);
        }
        return E164.matcher(compact).matches() ? Optional.of(compact) : Optional.empty();
    }

    /** Chiffres seuls, « 00 » initial retiré : clé de comparaison du rattrapage. */
    static String digitsKey(String raw) {
        if (raw == null) {
            return "";
        }
        String digits = NON_DIGITS.matcher(raw).replaceAll("");
        return digits.startsWith("00") ? digits.substring(2) : digits;
    }
}
