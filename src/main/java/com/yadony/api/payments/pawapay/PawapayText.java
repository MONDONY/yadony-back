package com.yadony.api.payments.pawapay;

/**
 * Bornage commun des valeurs qui viennent de pawaPay ou d'un appelant anonyme avant qu'elles
 * ne soient écrites (audit, événement), journalisées ou reflétées dans un message d'erreur.
 * La borne est celle de {@code pawapay_operations.failure_code} ({@code VARCHAR(64)}) ; elle
 * sert aussi de longueur maximale pour tout texte non authentifié reflété dans un journal, où
 * une charge arbitrairement longue (avec retours à la ligne) forgerait de fausses entrées.
 */
public final class PawapayText {

    public static final int MAX_LENGTH = 64;

    private PawapayText() {}

    /** Tronque à {@link #MAX_LENGTH} caractères ; {@code null} reste {@code null}. */
    public static String clamp(String value) {
        return value != null && value.length() > MAX_LENGTH ? value.substring(0, MAX_LENGTH) : value;
    }

    /**
     * Prépare une valeur non authentifiée pour une ligne de journal : chaque caractère de
     * contrôle (retours à la ligne compris, avec lesquels un appelant forgerait de fausses
     * entrées) devient un espace, puis la valeur est tronquée à {@code max} caractères.
     * {@code null} reste {@code null}.
     */
    public static String forLog(String value, int max) {
        if (value == null) return null;
        String flat = value.replaceAll("\\p{Cntrl}", " ");
        return flat.length() > max ? flat.substring(0, max) : flat;
    }
}
