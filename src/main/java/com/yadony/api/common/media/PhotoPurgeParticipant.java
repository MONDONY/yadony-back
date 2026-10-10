package com.yadony.api.common.media;

import java.time.LocalDateTime;

/**
 * Un package qui possède des photos à supprimer N jours après la livraison (ou la fin)
 * d'un colis. Implémenté par tracking/, requests/, signalements/ ; appelé par
 * {@link PhotoPurgeRunner} sans dépendance vers leurs services.
 *
 * <p>Contrat : sélectionner ce qui n'est PAS encore purgé et dont l'échéance est passée
 * (jamais « livré hier ») ; respecter les retenues (litige, signalement ouverts) ; en
 * {@code dryRun}, compter sans rien supprimer ni marquer ; ne marquer une ligne purgée
 * qu'après la suppression R2 réussie (un objet déjà absent compte comme supprimé) ;
 * traiter au plus {@code limit} objets.
 */
public interface PhotoPurgeParticipant {

    String name();

    PhotoPurgeOutcome purgeDue(int retentionDays, boolean dryRun, int limit, LocalDateTime now);
}
