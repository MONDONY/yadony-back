package com.yadony.api.common.commission;

/**
 * Avertissement levé à la programmation d'une grille : jamais un blocage, l'administrateur
 * l'acquitte explicitement pour poursuivre.
 */
public enum CommissionGridWarning {

    /** La grille augmente la commission (au moins un net de 1 € à 1 000 €, ou un minimum) et prend effet dans moins de 30 jours. */
    INCREASE_WITHIN_30_DAYS
}
