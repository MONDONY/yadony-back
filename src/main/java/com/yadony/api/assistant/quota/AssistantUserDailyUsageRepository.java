package com.yadony.api.assistant.quota;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Requêtes atomiques sur les questions du jour. Les écritures du quota n'ont pas de {@code @Transactional}
 * ici : elles rejoignent la transaction courte ouverte par {@link JpaAssistantUserDailyUsageStore}. La purge,
 * une seule requête, porte la sienne.
 */
public interface AssistantUserDailyUsageRepository
        extends JpaRepository<AssistantUserDailyUsageEntity, AssistantUserDailyUsageEntity.Key> {

    /** Crée la ligne du jour à zéro si elle n'existe pas ; deux créations simultanées ne se gênent pas. */
    @Modifying
    @Query(value = """
            INSERT INTO assistant_user_daily_usage (firebase_uid, usage_date, questions, updated_at)
            VALUES (:uid, :day, 0, CURRENT_TIMESTAMP)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("uid") String uid, @Param("day") LocalDate day);

    /**
     * Compte une question seulement si le quota n'est pas atteint. Le verrou de ligne de l'UPDATE sérialise
     * les questions simultanées (même sur deux instances), et la condition est relue après l'attente.
     *
     * @return 1 si la question est comptée, 0 si le quota du jour est atteint
     */
    @Modifying
    @Query(value = """
            UPDATE assistant_user_daily_usage
               SET questions = questions + 1, updated_at = CURRENT_TIMESTAMP
             WHERE firebase_uid = :uid AND usage_date = :day AND questions < :limit
            """, nativeQuery = true)
    int incrementBelow(@Param("uid") String uid, @Param("day") LocalDate day, @Param("limit") int limit);

    /** Lecture native, sans entité dans le contexte de persistance : la valeur vient de la base. */
    @Query(value = """
            SELECT questions FROM assistant_user_daily_usage WHERE firebase_uid = :uid AND usage_date = :day
            """, nativeQuery = true)
    Optional<Integer> findQuestions(@Param("uid") String uid, @Param("day") LocalDate day);

    @Transactional
    @Modifying
    @Query(value = "DELETE FROM assistant_user_daily_usage WHERE usage_date < :before", nativeQuery = true)
    int deleteOlderThan(@Param("before") LocalDate before);
}
