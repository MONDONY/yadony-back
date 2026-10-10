package com.yadony.api.common.media;

import java.util.Set;

/**
 * Une table qui référence des objets R2. Le rapprochement supprime toute clé qu'AUCUNE
 * source ne revendique : oublier une table ici, c'est effacer ses photos. Tout nouveau
 * stockage de clé R2 doit ajouter sa source (garde : ReferenceSourceCoverageIT).
 */
public interface MediaKeyReferenceSource {

    Set<String> prefixes();

    /**
     * Clés vivantes commençant par {@code prefix}, URL http(s) héritées ramenées à leur clé
     * ({@link MediaKeys#normalize}). En cas de doute, revendiquer : une clé revendiquée à tort
     * coûte un objet gardé, une clé oubliée coûte une photo effacée.
     */
    Set<String> referencedKeys(String prefix);
}
