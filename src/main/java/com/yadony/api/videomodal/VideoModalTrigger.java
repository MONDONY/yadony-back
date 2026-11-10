package com.yadony.api.videomodal;

/** Endroit de l'app où un modal vidéo peut s'ouvrir. */
public enum VideoModalTrigger {
    /** Premier écran après lancement, une fois l'app prête. */
    APP_OPEN,
    SEARCH_TAB,
    ACTIVITIES_TAB,
    /** Onglet Suivi. */
    TRACKING_TAB,
    MESSAGES_TAB,
    /** Onglet Moi. */
    PROFILE_TAB,
    /** Bouton « Publier un trajet » : modal AVANT le formulaire. */
    PUBLISH_TRIP,
    /** Bouton « Envoyer un colis » : modal AVANT le formulaire. */
    SEND_PARCEL
}
