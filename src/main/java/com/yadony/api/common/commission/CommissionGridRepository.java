package com.yadony.api.common.commission;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface CommissionGridRepository extends JpaRepository<CommissionGridEntity, UUID> {

    /** Toutes les grilles non annulées, la plus récente d'abord. */
    List<CommissionGridEntity> findAllByOrderByEffectiveFromDesc();

    /** Grilles pas encore en vigueur à {@code instant} : celle que la prochaine programmation remplace. */
    List<CommissionGridEntity> findByEffectiveFromAfter(LocalDateTime instant);

    /**
     * Historique complet, grilles annulées comprises. Requête native : le {@code @Where} de l'entité
     * les masquerait. {@code pageable} ne doit porter aucun tri (l'ordre est fixé ici).
     */
    @Query(value = "SELECT * FROM commission_grids ORDER BY effective_from DESC, created_at DESC",
            countQuery = "SELECT count(*) FROM commission_grids", nativeQuery = true)
    Page<CommissionGridEntity> findHistory(Pageable pageable);
}
