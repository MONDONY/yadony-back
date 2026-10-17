package com.yadony.api.assistant.formdraft;

import com.yadony.api.payments.currency.SupportedCurrency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validation d'une valeur proposée par le modèle (ou renvoyée par l'app). Chaque méthode renvoie la valeur
 * normalisée, ou {@code null} quand elle est invalide : un champ invalide n'est jamais corrigé à l'aveugle.
 */
final class DraftFields {

    private static final Pattern TIME = Pattern.compile("([01]?\\d|2[0-3]):([0-5]\\d)");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+([.,]\\d+)?");

    private DraftFields() {
    }

    static String text(Object v, int min, int max) {
        if (!(v instanceof String s)) return null;
        String t = s.strip();
        if (t.length() < min || t.length() > max) return null;
        for (int i = 0; i < t.length(); i++) {
            if (Character.isISOControl(t.charAt(i))) return null;
        }
        return t;
    }

    static String country(Object v) {
        if (!(v instanceof String s)) return null;
        String t = s.strip();
        return t.matches("[A-Za-z]{2}") ? t.toUpperCase(Locale.ROOT) : null;
    }

    static String date(Object v, LocalDate today, int maxDays) {
        if (!(v instanceof String s)) return null;
        LocalDate d;
        try {
            d = LocalDate.parse(s.strip());
        } catch (DateTimeParseException e) {
            return null;
        }
        return d.isBefore(today) || d.isAfter(today.plusDays(maxDays)) ? null : d.toString();
    }

    static String time(Object v) {
        if (!(v instanceof String s)) return null;
        Matcher m = TIME.matcher(s.strip());
        if (!m.matches()) return null;
        return String.format(Locale.ROOT, "%02d:%s", Integer.parseInt(m.group(1)), m.group(2));
    }

    static BigDecimal decimal(Object v, BigDecimal min, BigDecimal max) {
        BigDecimal d;
        if (v instanceof Number n) {
            double x = n.doubleValue();
            if (Double.isNaN(x) || Double.isInfinite(x)) return null;
            d = new BigDecimal(n.toString());
        } else if (v instanceof String s && NUMBER.matcher(s.strip()).matches()) {
            d = new BigDecimal(s.strip().replace(',', '.'));
        } else {
            return null;
        }
        if (d.compareTo(min) < 0 || d.compareTo(max) > 0) return null;
        d = d.stripTrailingZeros();
        return d.scale() < 0 ? d.setScale(0) : d;
    }

    static Integer integer(Object v, int min, int max) {
        BigDecimal d = decimal(v, BigDecimal.valueOf(min), BigDecimal.valueOf(max));
        if (d == null || d.scale() > 0) return null;
        return d.intValueExact();
    }

    static Boolean bool(Object v) {
        return v instanceof Boolean b ? b : null;
    }

    static String oneOf(Object v, Set<String> allowed) {
        if (!(v instanceof String s)) return null;
        String t = s.strip().toUpperCase(Locale.ROOT);
        return allowed.contains(t) ? t : null;
    }

    static <E extends Enum<E>> String enumName(Object v, Class<E> type) {
        if (!(v instanceof String s)) return null;
        String t = s.strip().toUpperCase(Locale.ROOT);
        for (E e : type.getEnumConstants()) {
            if (e.name().equals(t)) return t;
        }
        return null;
    }

    static String currency(Object v) {
        return enumName(v, SupportedCurrency.class);
    }

    static List<String> texts(Object v, int maxItems, int min, int max) {
        if (!(v instanceof List<?> list)) return null;
        Set<String> out = new LinkedHashSet<>();
        for (Object o : list) {
            String t = text(o, min, max);
            if (t != null) out.add(t);
            if (out.size() == maxItems) break;
        }
        return out.isEmpty() ? null : List.copyOf(out);
    }

    static List<String> subset(Object v, Set<String> allowed) {
        if (!(v instanceof List<?> list)) return null;
        Set<String> out = new LinkedHashSet<>();
        for (Object o : list) {
            String t = oneOf(o, allowed);
            if (t != null) out.add(t);
        }
        return out.isEmpty() ? null : new ArrayList<>(out);
    }
}
