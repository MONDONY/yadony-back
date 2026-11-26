package com.yadony.api.videomodal;

/** Fréquence d'affichage d'une campagne pour une personne. */
public enum VideoModalFrequency {
    /** Une seule fois par version (équivaut à {@code UP_TO_N} avec 1). */
    ONCE,
    /** Jusqu'à {@code maxDisplays} fois par version : le comportement historique. */
    UP_TO_N,
    /** À chaque lancement de l'app (un seul modal par lancement, appliqué côté app). */
    EVERY_OPEN,
    /** Une fois tous les {@code frequencyDays} jours. */
    EVERY_N_DAYS,
    /**
     * À chaque occurrence du moment d'action, sans plafond d'affichages et sans la limite « un seul
     * modal par session ». Réservée aux campagnes dont tous les déclencheurs sont des moments d'action.
     * Une app sans {@code caps=everytime} la reçoit sous la forme {@link #EVERY_OPEN}.
     */
    EVERY_TIME,
    /**
     * Une fois par colis : servie par le back comme {@link #EVERY_TIME} (aucun plafond d'affichages), l'app
     * dédoublonne dans son historique local (campagne, version, colis). Réservée aux campagnes dont tous les
     * déclencheurs sont des étapes du colis ; exige {@code caps=parcel} comme toute campagne d'étape.
     */
    ONCE_PER_PARCEL;

    /** Vrai pour les fréquences que les apps déjà installées (sans {@code caps=targeting}) ne savent pas gérer. */
    public boolean needsTargetingCapability() {
        return this == EVERY_OPEN || this == EVERY_N_DAYS || this == EVERY_TIME || this == ONCE_PER_PARCEL;
    }
}
