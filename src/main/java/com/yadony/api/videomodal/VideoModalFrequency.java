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
    EVERY_N_DAYS;

    /** Vrai pour les fréquences que les apps déjà installées (sans {@code caps=targeting}) ne savent pas gérer. */
    public boolean needsTargetingCapability() {
        return this == EVERY_OPEN || this == EVERY_N_DAYS;
    }
}
