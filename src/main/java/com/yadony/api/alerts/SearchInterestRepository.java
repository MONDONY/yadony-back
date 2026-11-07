package com.yadony.api.alerts;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SearchInterestRepository extends JpaRepository<SearchInterestEntity, UUID> {

    @Query("""
            SELECT s FROM SearchInterestEntity s
            WHERE s.userId = :userId
              AND lower(s.departureCity) = lower(:departureCity)
              AND lower(s.arrivalCity) = lower(:arrivalCity)
            """)
    Optional<SearchInterestEntity> findForCorridor(@Param("userId") UUID userId,
                                                   @Param("departureCity") String departureCity,
                                                   @Param("arrivalCity") String arrivalCity);

    /**
     * Intérêts à prévenir pour un trajet publié sur ce corridor : dernière recherche sans
     * résultat, pas encore prévenus, et récents (après {@code since}).
     */
    @Query("""
            SELECT s FROM SearchInterestEntity s
            WHERE lower(s.departureCity) = lower(:departureCity)
              AND lower(s.arrivalCity) = lower(:arrivalCity)
              AND s.resultCount = 0
              AND s.notifiedAt IS NULL
              AND s.lastSearchedAt >= :since
            """)
    List<SearchInterestEntity> findPendingForCorridor(@Param("departureCity") String departureCity,
                                                      @Param("arrivalCity") String arrivalCity,
                                                      @Param("since") LocalDateTime since);

    /** Suggestions déjà envoyées à cet utilisateur depuis {@code since} (plafond hebdomadaire). */
    long countByUserIdAndNotifiedAtAfter(UUID userId, LocalDateTime since);

    List<SearchInterestEntity> findAllByUserIdOrderByLastSearchedAtDesc(UUID userId);

    /**
     * Effacement définitif des recherches anciennes (minimisation RGPD). Exception assumée
     * à la suppression logique : garder un historique de recherches « supprimé » irait contre
     * le but même de la purge. Même choix que {@code assistant_user_daily_usage}.
     */
    @Transactional
    @Modifying
    @Query(value = "DELETE FROM search_interests WHERE last_searched_at < :before", nativeQuery = true)
    int purgeSearchedBefore(@Param("before") LocalDateTime before);

    /** Effacement à la finalisation du compte, même raison que {@link #purgeSearchedBefore}. */
    @Transactional
    @Modifying
    @Query(value = "DELETE FROM search_interests WHERE user_id = :userId", nativeQuery = true)
    int purgeByUserId(@Param("userId") UUID userId);
}
