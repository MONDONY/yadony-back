package com.yadony.api.notifications;

/**
 * Bornes de longueur des textes de notification, et les deux raccourcis qui les
 * font tenir.
 *
 * <p>Les chiffres viennent d'une mesure au rendu réel de l'app (Plus Jakarta
 * Sans, colonne de texte de 268 pt sur un écran de 390 pt) : un titre en gras
 * tient 29 caractères à côté d'un horodatage, un corps tient 37 à 40 caractères
 * par ligne et deux lignes en tiennent 72 à 80 selon la coupure des mots.
 * Voir {@code ~/Documents/dony-notifs-design/catalogue-gabarits.md}.
 *
 * <p>Deux règles trouvées en mesurant : on raccourcit la <em>variable</em>
 * ({@link #shortDisplayName}), jamais la phrase assemblée ; et on ne raccourcit
 * jamais un nom de ville, c'est de l'identité. Quand une coupure reste
 * inévitable, {@link #truncateAtWord} coupe au mot, jamais au milieu.
 */
public final class NotificationCaps {

    /** Titre : une ligne, jamais coupé à l'écran. */
    public static final int TITLE_MAX = 28;

    /** Corps : deux lignes ; au-delà l'app coupe par points de suspension. */
    public static final int BODY_MAX = 72;

    /** Nom affiché après réduction en « Prénom I. ». */
    public static final int DISPLAY_NAME_MAX = 16;

    private static final String ELLIPSIS = "…";

    private NotificationCaps() {}

    /**
     * Coupe {@code text} pour qu'il tienne en {@code max} caractères, points de
     * suspension compris, sur une limite de mot. Un texte déjà assez court est
     * rendu tel quel. Sans espace utilisable, coupe net.
     */
    public static String truncateAtWord(String text, int max) {
        if (text == null || text.length() <= max) return text;
        int room = max - ELLIPSIS.length();
        if (room <= 0) return ELLIPSIS;
        String head;
        if (!Character.isLetterOrDigit(text.charAt(room))) {
            // La limite tombe sur une frontière de mot : tout ce qui précède tient.
            head = text.substring(0, room);
        } else {
            int cut = text.lastIndexOf(' ', room);
            head = cut > 0 ? text.substring(0, cut) : text.substring(0, room);
        }
        head = stripTrailingPunctuation(head);
        return head + ELLIPSIS;
    }

    /**
     * Réduit un nom complet à « Prénom I. », I étant l'initiale du mot qui suit
     * le prénom (« Mohammed Abdoulaye Diallo » devient « Mohammed A. »), borné à
     * {@link #DISPLAY_NAME_MAX}. Un prénom seul reste tel quel. Un nom nul ou
     * vide est rendu tel quel : le repli d'affichage est l'affaire de
     * {@code UserEntity.publicDisplayName()}, source unique du nom, jamais d'ici.
     */
    public static String shortDisplayName(String fullName) {
        if (fullName == null || fullName.isBlank()) return fullName;
        String[] parts = fullName.trim().split("\\s+");
        String name = parts.length == 1
                ? parts[0]
                : parts[0] + " " + parts[1].charAt(0) + ".";
        return name.length() <= DISPLAY_NAME_MAX ? name : truncateAtWord(name, DISPLAY_NAME_MAX);
    }

    private static String stripTrailingPunctuation(String s) {
        int end = s.length();
        while (end > 0 && ",;:.!?".indexOf(s.charAt(end - 1)) >= 0) end--;
        return s.substring(0, end).stripTrailing();
    }
}
