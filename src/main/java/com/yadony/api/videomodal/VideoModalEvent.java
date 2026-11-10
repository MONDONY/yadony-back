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
    COMPLETED(3);

    private final int rank;

    VideoModalEvent(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    /** Répondu = OPENED, COMPLETED ou DISMISSED. */
    public boolean isAnswer() {
        return this != SHOWN;
    }

    /** La vidéo a été ouverte (OPENED ou COMPLETED). */
    public boolean isWatched() {
        return this == OPENED || this == COMPLETED;
    }
}
