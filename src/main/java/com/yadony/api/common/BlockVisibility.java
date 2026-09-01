package com.yadony.api.common;

import java.util.Set;
import java.util.UUID;

/**
 * Masquage mutuel des utilisateurs qui se sont bloqués.
 *
 * <p>Contrat exposé dans {@code common/} pour que chaque feature applique la règle sans
 * dépendre du service de {@code auth/} qui la porte : le blocage est transverse, mais son
 * implémentation reste un détail du package qui possède la table {@code user_blocks}.
 *
 * <p>Deux invariants tiennent toute la feature :
 * <ul>
 *   <li><b>symétrique</b> — un blocage dans un sens masque dans les deux ;</li>
 *   <li><b>silencieux</b> — on masque par 404, jamais 403, pour qu'un blocage reste
 *       indétectable par celui qui en fait l'objet.</li>
 * </ul>
 *
 * <p>Exception unique : tant qu'une transaction est en cours entre les deux, rien n'est
 * masqué — couper la coordination en plein acheminement ferait plus de dégâts que le
 * blocage n'en évite.
 */
public interface BlockVisibility {

    /** Le contenu de {@code targetId} doit-il être invisible pour {@code viewerId} ? */
    boolean isHidden(UUID viewerId, UUID targetId);

    /** Lève un 404 si {@code targetId} est masqué pour {@code viewerId}. */
    void assertVisible(UUID viewerId, UUID targetId);

    /** IDs à retirer des listes présentées à {@code viewerId}. */
    Set<UUID> hiddenUserIdsFor(UUID viewerId);
}
