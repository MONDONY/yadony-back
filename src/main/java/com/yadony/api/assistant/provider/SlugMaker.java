package com.yadony.api.assistant.provider;

import java.text.Normalizer;
import java.util.Locale;
import java.util.function.Predicate;

/** Identifiant court et stable d'un fournisseur, dérivé de son nom à la création. */
public final class SlugMaker {

    private static final int MAX = 40;

    private SlugMaker() {
    }

    public static String slugify(String name) {
        String s = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (s.length() > MAX) s = s.substring(0, MAX).replaceAll("-+$", "");
        return s.isEmpty() ? "fournisseur" : s;
    }

    public static String unique(String name, Predicate<String> taken) {
        String base = slugify(name);
        if (!taken.test(base)) return base;
        for (int i = 2; ; i++) {
            String suffix = "-" + i;
            String candidate = (base.length() + suffix.length() > MAX ? base.substring(0, MAX - suffix.length()) : base) + suffix;
            if (!taken.test(candidate)) return candidate;
        }
    }
}
