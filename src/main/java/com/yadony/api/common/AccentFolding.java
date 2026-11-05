package com.yadony.api.common;

/**
 * Table de repli des diacritiques latins, pour comparer en SQL sans accent des noms qui
 * en gardent en base (« Thiès », « Lomé », « Bouaké »).
 *
 * <p>L'extension {@code unaccent} n'est pas disponible : on passe par {@code translate()},
 * du SQL standard, en lui donnant ces deux listes. Elles se lisent position à position
 * (chaque caractère de {@link #ACCENTED} devient celui de même rang dans
 * {@link #UNACCENTED}), donc elles doivent rester de même longueur. Le jeu couvre le
 * corridor du produit (français et Afrique francophone).
 *
 * <p>À employer des DEUX côtés de la comparaison : sur la colonne et sur la saisie.
 */
public final class AccentFolding {

    /** Diacritiques latins courants, en minuscules (à utiliser après {@code lower()}). */
    public static final String ACCENTED = "àâäáãåèéêëìíîïòóôöõùúûüçñýÿ";

    /** Forme sans accent, dans le même ordre que {@link #ACCENTED}. */
    public static final String UNACCENTED = "aaaaaaeeeeiiiioooooouuuucnyy";

    private AccentFolding() {}
}
