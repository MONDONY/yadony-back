package com.yadony.api.assistant.llm;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Requêtes atomiques sur les compteurs du jour. Pas de {@code @Transactional} ici : elles rejoignent
 * la transaction courte ouverte par {@link JpaProviderDailyUsageStore}.
 */
public interface ProviderDailyUsageRepository extends JpaRepository<ProviderDailyUsageEntity, ProviderDailyUsageEntity.Key> {

    /** Crée la ligne du jour à zéro si elle n'existe pas ; deux créations simultanées ne se gênent pas. */
    @Modifying
    @Query(value = """
            INSERT INTO assistant_provider_daily_usage (provider_id, usage_date, requests, successes, updated_at)
            VALUES (:providerId, :day, 0, 0, CURRENT_TIMESTAMP)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("providerId") UUID providerId, @Param("day") LocalDate day);

    /**
     * Réserve un essai : incrémente seulement si la limite n'est pas atteinte. Le verrou de ligne de
     * l'UPDATE sérialise les réservations concurrentes, et la condition est relue après l'attente.
     *
     * @return 1 si l'essai est réservé, 0 si la limite du jour est atteinte
     */
    @Modifying
    @Query(value = """
            UPDATE assistant_provider_daily_usage
               SET requests = requests + 1, updated_at = CURRENT_TIMESTAMP
             WHERE provider_id = :providerId AND usage_date = :day AND requests < :limit
            """, nativeQuery = true)
    int incrementRequestsBelow(@Param("providerId") UUID providerId, @Param("day") LocalDate day,
                               @Param("limit") int limit);

    @Modifying
    @Query(value = """
            UPDATE assistant_provider_daily_usage
               SET successes = successes + 1, updated_at = CURRENT_TIMESTAMP
             WHERE provider_id = :providerId AND usage_date = :day
            """, nativeQuery = true)
    int incrementSuccesses(@Param("providerId") UUID providerId, @Param("day") LocalDate day);

    /** Projection : lit les valeurs en base sans charger d'entité dans le contexte de persistance. */
    @Query("""
            SELECT new com.yadony.api.assistant.llm.ProviderDailyUsageStore$DailyCount(
                       u.id.providerId, u.requests, u.successes)
              FROM ProviderDailyUsageEntity u
             WHERE u.id.usageDate = :day AND u.id.providerId IN :providerIds
            """)
    List<ProviderDailyUsageStore.DailyCount> findCounts(@Param("providerIds") Collection<UUID> providerIds,
                                                        @Param("day") LocalDate day);
}
