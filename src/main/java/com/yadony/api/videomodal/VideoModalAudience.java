package com.yadony.api.videomodal;

/** Public d'une version de campagne. */
public enum VideoModalAudience {
    /** Utilisateurs qui n'ont répondu à AUCUNE version de la campagne. */
    NEVER_ANSWERED,
    /** Tout le monde, même ceux qui ont répondu aux versions précédentes. */
    EVERYONE,
    /** Utilisateurs qui n'ont jamais ouvert la vidéo (OPENED ou COMPLETED) d'une version précédente. */
    NOT_WATCHED_PREVIOUS
}
