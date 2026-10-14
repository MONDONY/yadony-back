package com.yadony.api.referral;

import com.yadony.api.auth.UserEntity;

/** Nom d'un filleul tel que le parrain le voit : prénom + initiale du nom, jamais le téléphone. */
final class RefereeNames {

    private RefereeNames() {}

    /** « Awa D. », « Awa » sans nom, {@code null} sans prénom. */
    static String displayName(UserEntity referee) {
        if (referee == null) return null;
        String first = trimToNull(referee.getFirstName());
        if (first == null) return null;
        String last = trimToNull(referee.getLastName());
        return last == null ? first : first + " " + last.substring(0, last.offsetByCodePoints(0, 1)).toUpperCase() + ".";
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
