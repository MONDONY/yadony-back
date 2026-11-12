package com.yadony.api.common.i18n;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Date passée en argument d'un message : {@link Messages} l'écrit dans la langue de la réponse
 * (FR « 1er décembre 2026 », « 15 décembre 2026 » ; EN « December 1, 2026 »), au lieu de l'ISO
 * {@code 2026-12-01} que donnerait un {@link LocalDate} brut.
 */
public record LocalizedDate(LocalDate date) {

    private static final DateTimeFormatter FR_MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRENCH);
    private static final DateTimeFormatter EN = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);

    /** Texte de la date dans la langue donnée. */
    public String format(AppLanguage language) {
        return switch (language) {
            case FR -> (date.getDayOfMonth() == 1 ? "1er" : String.valueOf(date.getDayOfMonth()))
                    + " " + date.format(FR_MONTH);
            case EN -> date.format(EN);
        };
    }
}
