package com.yadony.api.videomodal;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Versions de l'app : {@code x.y.z} côté campagne (strict), lecture tolérante de ce que l'app envoie. */
final class VideoModalAppVersions {

    private static final Pattern STRICT = Pattern.compile("^(\\d{1,4})\\.(\\d{1,4})\\.(\\d{1,4})$");
    private static final Pattern LENIENT = Pattern.compile("^v?(\\d{1,4})(?:\\.(\\d{1,4}))?(?:\\.(\\d{1,4}))?(?:[+\\-].*)?$");

    private VideoModalAppVersions() {
    }

    /** Version d'une campagne : exactement {@code x.y.z}. */
    static Optional<int[]> parseStrict(String raw) {
        if (raw == null) return Optional.empty();
        Matcher m = STRICT.matcher(raw.trim());
        return m.matches() ? Optional.of(groups(m)) : Optional.empty();
    }

    /** Version envoyée par l'app : {@code 1.0.6}, {@code 1.0.6+1053}, {@code 1.0} ; illisible = inconnue. */
    static Optional<int[]> parseLenient(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        Matcher m = LENIENT.matcher(raw.trim());
        return m.matches() ? Optional.of(groups(m)) : Optional.empty();
    }

    static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            int c = Integer.compare(a[i], b[i]);
            if (c != 0) return c;
        }
        return 0;
    }

    private static int[] groups(Matcher m) {
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            String g = m.group(i + 1);
            out[i] = g == null ? 0 : Integer.parseInt(g);
        }
        return out;
    }
}
