package com.yadony.api.common.deletion;

import java.util.List;
import java.util.UUID;

/**
 * Ce qu'un package a à dire sur la suppression d'un compte.
 *
 * <p>Chaque package métier en déclare une implémentation ; {@code admin} injecte la liste
 * complète sans connaître aucun d'eux. La dépendance est inversée, donc l'interdiction
 * d'injection cross-package du projet est respectée — les Spring Events ne conviennent pas ici,
 * étant fire-and-forget et incapables de rapporter un résultat attendu.
 *
 * <p>Une implémentation qui n'a rien à signaler rend une liste vide, jamais {@code null}.
 */
public interface UserDeletionImpactContributor {
    List<ImpactFinding> contribute(UUID userId);
}
