package com.yadony.api.dac7;

/** Démarche inscrite au registre des diligences DAC7 (mêmes valeurs que le CHECK de V375). */
public enum Dac7DueDiligenceAction {
    /** Première demande du NIF (au premier versement, lot 3b). */
    REQUEST,
    REMINDER_1,
    REMINDER_2,
    /** Le voyageur a fourni ou modifié ses informations fiscales. */
    PROVIDED,
    /** Décision de l'équipe après les rappels (jamais automatique, H9). */
    TEAM_DECISION
}
