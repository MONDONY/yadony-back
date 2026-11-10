package com.yadony.api.videomodal;

import java.util.Locale;

/** Système de l'appareil. */
public enum VideoModalPlatform {
    ANDROID, IOS;

    /** {@code android} / {@code ios} (casse libre) ; toute autre valeur donne {@code null} (plateforme inconnue). */
    public static VideoModalPlatform fromParam(String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "android" -> ANDROID;
            case "ios" -> IOS;
            default -> null;
        };
    }
}
