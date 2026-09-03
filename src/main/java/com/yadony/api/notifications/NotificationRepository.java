package com.yadony.api.notifications;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<NotificationEntity, UUID> {

    Page<NotificationEntity> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    long countByUserIdAndReadAtIsNull(UUID userId);

    @Modifying
    @Query("UPDATE NotificationEntity n SET n.readAt = :now WHERE n.userId = :userId AND n.readAt IS NULL")
    int markAllReadByUserId(UUID userId, LocalDateTime now);

    // ── Feed et boîte annonces (refonte du sheet, 2026-09) ───────────────────

    /**
     * Le feed : tout sauf les annonces plateforme, moins les non-lues d'un groupe
     * replié ({@code collapsedKeys}), que {@link NotificationFeedService} remplace
     * par une ligne agrégée. Une notification déjà lue reste une ligne à elle,
     * même si son groupe est replié : seul le non-lu s'agrège.
     * {@code collapsedKeys} ne doit jamais être vide (JPQL) : le service passe
     * une sentinelle.
     */
    @Query("""
            SELECT n FROM NotificationEntity n
            WHERE n.userId = :userId
              AND n.category <> :excluded
              AND (n.groupKey IS NULL OR n.readAt IS NOT NULL OR n.groupKey NOT IN :collapsedKeys)
            ORDER BY n.createdAt DESC
            """)
    Page<NotificationEntity> findFeed(@Param("userId") UUID userId,
                                      @Param("excluded") NotificationCategory excluded,
                                      @Param("collapsedKeys") Collection<String> collapsedKeys,
                                      Pageable pageable);

    /** Candidates à l'agrégation : non lues et porteuses d'une clé partagée, plus récente en tête. */
    List<NotificationEntity> findByUserIdAndReadAtIsNullAndGroupKeyIsNotNullOrderByCreatedAtDesc(UUID userId);

    Page<NotificationEntity> findByUserIdAndCategoryOrderByCreatedAtDesc(UUID userId, NotificationCategory category,
                                                                         Pageable pageable);

    long countByUserIdAndCategoryAndReadAtIsNull(UUID userId, NotificationCategory category);

    Optional<NotificationEntity> findFirstByUserIdAndCategoryOrderByCreatedAtDesc(UUID userId,
                                                                                  NotificationCategory category);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE NotificationEntity n SET n.readAt = :now
            WHERE n.userId = :userId AND n.groupKey = :groupKey AND n.readAt IS NULL
            """)
    int markGroupRead(@Param("userId") UUID userId, @Param("groupKey") String groupKey,
                      @Param("now") LocalDateTime now);

    // Story 8.3 — SMS fallback: critical notifications with no ACK and no SMS after cutoff
    @Query("""
            SELECT n FROM NotificationEntity n
            WHERE n.isCritical = true
              AND n.ackedAt   IS NULL
              AND n.smsSentAt IS NULL
              AND n.createdAt  < :cutoff
            """)
    List<NotificationEntity> findPendingSmsFallbacks(@Param("cutoff") LocalDateTime cutoff);
}