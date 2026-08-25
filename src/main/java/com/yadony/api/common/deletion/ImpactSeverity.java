package com.yadony.api.common.deletion;

/**
 * Gravité d'un constat d'impact. L'ordre de déclaration porte le tri d'affichage :
 * ce qui empêche la suppression passe avant ce qui la complique.
 */
public enum ImpactSeverity {
    /** Empêche la suppression tant que ce n'est pas soldé. */
    BLOCKING,
    /** N'empêche pas la suppression mais touche des comptes tiers. */
    WARNING,
    /** Contexte, sans conséquence pour un tiers. */
    INFO
}
