package com.yadony.api.signalements;

/**
 * Origine d'un signalement (V365). {@code USER} : fait depuis l'app par un utilisateur.
 * {@code SYSTEM} : créé par Yadony, sans signalant (détection automatique).
 */
public enum ReportSource {
    USER,
    SYSTEM
}
