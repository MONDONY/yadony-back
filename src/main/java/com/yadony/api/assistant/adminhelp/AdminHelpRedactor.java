package com.yadony.api.assistant.adminhelp;

import java.util.regex.Pattern;

/**
 * Masque les données personnelles qu'un admin pourrait coller dans sa question (email, téléphone, identifiant,
 * numéro de carte) avant tout envoi au fournisseur d'IA. La doc ne parle jamais d'une personne : rien d'utile
 * n'est perdu.
 */
final class AdminHelpRedactor {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern UUID = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    /** Suites d'au moins 6 chiffres, espaces, points ou tirets compris (téléphones, IBAN, cartes). */
    private static final Pattern NUMBER = Pattern.compile("\\+?\\d[\\d .\\-]{4,}\\d");

    private AdminHelpRedactor() {
    }

    static String redact(String text) {
        if (text == null) return "";
        String out = EMAIL.matcher(text).replaceAll("[email]");
        out = UUID.matcher(out).replaceAll("[identifiant]");
        return NUMBER.matcher(out).replaceAll("[numéro]");
    }
}
