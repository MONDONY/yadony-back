package com.yadony.api.admin;

import com.yadony.api.admin.broadcast.rule.RuleAudienceRepository.RecipientRow;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * CSV des destinataires d'un broadcast : BOM UTF-8, séparateur {@code ;} (Excel français),
 * aucune coordonnée. Un nom contenant {@code ;}, {@code "} ou un saut de ligne est mis entre
 * guillemets, et une cellule commençant par {@code = + - @} est neutralisée par une apostrophe.
 */
final class BroadcastRecipientsCsv {

    static final String HEADER = "id;nom;pays;langue;push;inscrit_le";

    private BroadcastRecipientsCsv() {
    }

    static byte[] write(List<RecipientRow> rows) {
        StringBuilder sb = new StringBuilder("﻿").append(HEADER).append('\n');
        for (RecipientRow r : rows) {
            sb.append(cell(r.userId() == null ? null : r.userId().toString())).append(';')
                    .append(cell(r.displayName())).append(';')
                    .append(cell(r.country())).append(';')
                    .append(cell(r.language())).append(';')
                    .append(r.pushReachable() ? "oui" : "non").append(';')
                    .append(cell(r.signedUpAt() == null ? null : r.signedUpAt().toString()))
                    .append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String cell(String value) {
        if (value == null) {
            return "";
        }
        String v = value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        boolean quote = v.indexOf(';') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
        return quote ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }
}
