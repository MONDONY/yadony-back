package com.yadony.api.admin.export;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * CSV des exports admin : BOM UTF-8 (Excel lit les accents), champs entre guillemets au besoin,
 * et cellules texte commençant par {@code = + - @} neutralisées par une apostrophe — un nom
 * d'utilisateur « =HYPERLINK(…) » ne doit pas devenir une formule dans le tableur de la compta.
 */
public final class CsvWriter {

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");

    private final StringBuilder sb;

    public CsvWriter(String header) {
        sb = new StringBuilder("﻿").append(header).append('\n');
    }

    public CsvWriter row(String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(cell(cells[i]));
        }
        sb.append('\n');
        return this;
    }

    public byte[] bytes() {
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String cell(String value) {
        if (value == null) return "";
        String v = value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0 && !NUMBER.matcher(v).matches()) {
            v = "'" + v;
        }
        return v.contains(",") || v.contains("\"") || v.contains("\n")
                ? "\"" + v.replace("\"", "\"\"") + "\""
                : v;
    }
}
