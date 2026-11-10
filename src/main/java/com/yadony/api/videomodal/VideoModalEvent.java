package com.yadony.api.videomodal;

/**
 * Événement envoyé par l'app, qui sert aussi de statut de la réponse d'un utilisateur à une
 * version. Le rang ordonne les statuts : un statut n'est jamais rétrogradé
 * (COMPLETED > OPENED > DISMISSED > SHOWN).
 */
public enum VideoModalEvent {
    /** Modal affiché : incrémente le compteur d'affichages, n'est pas une réponse. */
    SHOWN(0),
    /** Bouton secondaire touché : réponse. */
    DISMISSED(1),
    /** Bouton vidéo touché : réponse. */
    OPENED(2),
    /** Vidéo vue jusqu'au bout : réponse. */
    COMPLETED(3),
    /**
     * Case « Ne plus afficher » cochée : réponse définitive pour la version courante, quelles que
     * soient la fréquence et {@code stopAfterAnswer}. N'est jamais un statut (rang négatif) et ne
     * compte ni comme réponse ni comme affichage : elle pose seulement un drapeau.
     */
    DONT_SHOW_AGAIN(-1);

    private final int rank;

    VideoModalEvent(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    /** Répondu = OPENED, COMPLETED ou DISMISSED. */
    public boolean isAnswer() {
        return this == DISMISSED || this == OPENED || this == COMPLETED;
    }

    /** La vidéo a été ouverte (OPENED ou COMPLETED). */
    public boolean isWatched() {
        return this == OPENED || this == COMPLETED;
    }
}
