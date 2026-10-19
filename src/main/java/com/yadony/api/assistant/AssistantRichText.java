package com.yadony.api.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Texte « riche » des réponses de l'assistant (YADONY-FLUTTER-PROD-13) : une liste blanche, rendue par
 * l'app sans paquet Markdown. Seuls survivent {@code **gras**}, les puces {@code - } en début de ligne
 * et les retours à la ligne ; titres, liens, tableaux, code, italiques et listes imbriquées sont aplatis
 * en texte. {@link #plain} en dérive la version brute ({@code answer}) que lisent l'historique,
 * l'escalade vers le support et les anciennes versions de l'app.
 */
final class AssistantRichText {

    static final String BOLD = "**";
    static final String BULLET = "- ";
    /** Puce de la version brute : lisible telle quelle dans un {@code Text} ou un ticket. */
    static final String PLAIN_BULLET = "• ";
    static final int MAX_BOLD = 3;

    private static final Pattern LINK = Pattern.compile("\\[([^\\]\\n]*)]\\(([^)\\n]*)\\)");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s*");
    private static final Pattern LIST_MARK = Pattern.compile("^(?:[-*+•]|\\d{1,2}[.)])\\s+");
    private static final Pattern TABLE_RULE = Pattern.compile("^[|:\\-\\s]+$");

    private AssistantRichText() {
    }

    /** Applique la liste blanche ; chaîne vide si rien ne reste. */
    static String sanitize(String raw) {
        if (raw == null) return "";
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        text = LINK.matcher(text).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(m.group(1)));
        text = text.replace("`", "").replace("__", "");
        List<String> lines = new ArrayList<>();
        int[] boldLeft = {MAX_BOLD};
        for (String rawLine : text.split("\n", -1)) {
            String line = rawLine.strip();
            if (line.isEmpty()) continue;
            if (line.contains("|") && TABLE_RULE.matcher(line).matches()) continue;
            line = line.replace('|', ' ');
            line = HEADING.matcher(line).replaceFirst("");
            boolean bullet = false;
            var mark = LIST_MARK.matcher(line);
            // « **Montant** : … » commence par une étoile mais n'est pas une puce.
            if (!line.startsWith(BOLD) && mark.find()) {
                bullet = true;
                line = line.substring(mark.end());
            }
            line = bold(line, boldLeft).replaceAll("\\s+", " ").strip();
            if (line.isEmpty() || line.equals(BOLD)) continue;
            lines.add(bullet ? BULLET + line : line);
        }
        return String.join("\n", lines);
    }

    /**
     * Garde les paires {@code **…**} non vides, dans la limite du budget ; retire le reste des étoiles
     * (orphelines, italiques {@code *x*}, paires vides).
     */
    private static String bold(String line, int[] left) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < line.length()) {
            int open = line.indexOf(BOLD, i);
            if (open < 0) {
                out.append(stripStars(line.substring(i)));
                break;
            }
            int close = line.indexOf(BOLD, open + 2);
            out.append(stripStars(line.substring(i, open)));
            if (close < 0) {
                // Orpheline : le texte reste, l'étoile part.
                out.append(stripStars(line.substring(open + 2)));
                break;
            }
            String inner = stripStars(line.substring(open + 2, close)).strip();
            if (!inner.isEmpty()) {
                if (left[0] > 0) {
                    out.append(BOLD).append(inner).append(BOLD);
                    left[0]--;
                } else {
                    out.append(inner);
                }
            }
            i = close + 2;
        }
        return out.toString();
    }

    private static String stripStars(String s) {
        return s.replace("*", "");
    }

    /** Version brute : sans gras, puces {@code •}. */
    static String plain(String rich) {
        if (rich == null) return "";
        List<String> lines = new ArrayList<>();
        for (String line : rich.split("\n", -1)) {
            String l = line.replace(BOLD, "");
            lines.add(l.startsWith(BULLET) ? PLAIN_BULLET + l.substring(BULLET.length()) : l);
        }
        return String.join("\n", lines);
    }

    /**
     * Coupe à {@code max} caractères au plus : sur une fin de ligne si elle garde au moins la moitié du
     * texte, sinon sur une fin de mot suivie de « … » ; un gras ouvert par la coupe est refermé.
     */
    static String truncate(String rich, int max) {
        if (rich.length() <= max) return rich;
        int end = max - 1;
        if (Character.isHighSurrogate(rich.charAt(end - 1))) end--;
        String head = rich.substring(0, end);
        int newline = head.lastIndexOf('\n');
        String cut;
        if (newline >= max / 2) {
            cut = head.substring(0, newline).strip();
        } else {
            int space = head.lastIndexOf(' ');
            cut = (space > 0 ? head.substring(0, space) : head).strip();
            if (cut.endsWith(BOLD)) cut = cut.substring(0, cut.length() - 2).strip();
            cut = repairBold(cut) + "…";
            return cut;
        }
        return repairBold(cut);
    }

    /** Referme le dernier gras d'une ligne coupée au milieu (nombre impair de {@code **}). */
    private static String repairBold(String text) {
        int lineStart = text.lastIndexOf('\n') + 1;
        String last = text.substring(lineStart);
        int count = 0;
        for (int i = last.indexOf(BOLD); i >= 0; i = last.indexOf(BOLD, i + 2)) count++;
        if (count % 2 == 0) return text;
        int lastOpen = last.lastIndexOf(BOLD);
        String inner = last.substring(lastOpen + 2).strip();
        String fixed = inner.isEmpty()
                ? last.substring(0, lastOpen).strip()
                : last.substring(0, lastOpen) + BOLD + inner + BOLD;
        return text.substring(0, lineStart) + fixed;
    }
}
