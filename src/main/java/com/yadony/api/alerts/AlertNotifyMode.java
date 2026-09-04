package com.yadony.api.alerts;

/**
 * Fréquence des notifications d'une alerte corridor. Ne touche jamais au
 * comptage des nouveautés ni aux correspondances : une alerte silencieuse
 * compte, elle ne pousse simplement rien.
 */
public enum AlertNotifyMode {
    /** Push dès qu'un trajet ou un colis matche ; le digest quotidien reste en filet. */
    INSTANT,
    /** Uniquement le digest quotidien. */
    DAILY,
    /** Aucune notification. */
    MUTED
}
