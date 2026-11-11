package com.yadony.api.dac7;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Registre en ajout seul : {@link Repository} et non {@code JpaRepository}, pour qu'aucune méthode de suppression ni
 * de mise à jour en masse n'existe côté Java (le trigger de V376 les refuse aussi côté base).
 */
public interface Dac7DueDiligenceLogRepository extends Repository<Dac7DueDiligenceLogEntity, Long> {

    Dac7DueDiligenceLogEntity save(Dac7DueDiligenceLogEntity entry);

    List<Dac7DueDiligenceLogEntity> findByUserIdOrderByCreatedAtAscIdAsc(java.util.UUID userId);

    /** Une démarche précise a-t-elle déjà été inscrite (ex. demande du seul pays à l'acceptation) ? */
    boolean existsByUserIdAndActionAndNote(UUID userId, Dac7DueDiligenceAction action, String note);

    /** La preuve d'une même vérification Didit est-elle déjà au registre ? */
    boolean existsByUserIdAndSourceAndVerificationRef(UUID userId, Dac7DiligenceSource source, String verificationRef);
}
