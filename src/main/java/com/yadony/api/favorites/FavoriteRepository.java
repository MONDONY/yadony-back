package com.yadony.api.favorites;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FavoriteRepository extends JpaRepository<FavoriteEntity, UUID> {

    boolean existsByUserIdAndTargetTypeAndTargetId(UUID userId, FavoriteTargetType targetType, UUID targetId);

    /**
     * Ajoute le favori s'il n'existe pas déjà, de façon atomique.
     *
     * <p>{@code ON CONFLICT DO NOTHING} (sans cible) couvre l'index unique partiel
     * {@code ux_favorites_active} (V152) : deux ajouts simultanés du même favori ne lèvent
     * plus de violation d'unicité. Un {@code save()} suivi d'un catch ne suffisait pas : dans
     * la transaction du service, l'INSERT partait au commit, hors du try, et la course
     * finissait en 500 (test de charge k6 du 07/10 : 16 % de PUT en échec à 40 VUs).
     *
     * @return 1 si la ligne a été insérée, 0 si le favori existait déjà
     */
    // @Transactional : une requête @Modifying exige une transaction. Rejoint celle de
    // l'appelant, ou commite seule quand il n'y en a pas (WalletService.getOrCreate).
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO favorites (id, user_id, target_type, target_id, created_at, updated_at)
            VALUES (:id, :userId, :targetType, :targetId, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("userId") UUID userId,
                       @Param("targetType") String targetType,
                       @Param("targetId") UUID targetId);

    /**
     * Supprime physiquement le favori s'il existe, en une requête : idempotent sous
     * concurrence. Charger puis {@code delete()} l'entité échouait pour la seconde de deux
     * suppressions simultanées (ligne déjà supprimée au flush, verrouillage optimiste en
     * 409) : 13 % des DELETE au test de charge k6 du 08/10.
     *
     * @return le nombre de lignes supprimées (0 ou 1)
     */
    @Transactional
    @Modifying
    @Query(value = """
            DELETE FROM favorites
            WHERE user_id = :userId AND target_type = :targetType AND target_id = :targetId
            """, nativeQuery = true)
    int deleteActive(@Param("userId") UUID userId,
                     @Param("targetType") String targetType,
                     @Param("targetId") UUID targetId);

    Optional<FavoriteEntity> findByUserIdAndTargetTypeAndTargetId(UUID userId, FavoriteTargetType targetType, UUID targetId);

    List<FavoriteEntity> findByUserIdAndTargetTypeOrderByCreatedAtDesc(UUID userId, FavoriteTargetType targetType);

    List<FavoriteEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);

    @Query("SELECT f.targetId FROM FavoriteEntity f WHERE f.userId = :userId AND f.targetType = :type")
    List<UUID> findTargetIds(@Param("userId") UUID userId, @Param("type") FavoriteTargetType type);

    /**
     * Suppression physique des favoris TRIP dont le trajet a atteint un état
     * terminal (COMPLETED/CANCELLED) — évite l'accumulation indéfinie de favoris
     * sur des trajets qui ne sont plus jamais actionnables. Idempotent.
     *
     * @return le nombre de lignes supprimées
     */
    @Modifying
    @Query(value = """
            DELETE FROM favorites
            WHERE target_type = 'TRIP'
              AND target_id IN (
                  SELECT id FROM announcements WHERE status IN ('COMPLETED', 'CANCELLED')
              )
            """, nativeQuery = true)
    int deleteTripFavoritesForTerminalAnnouncements();

    /**
     * Même nettoyage pour les favoris PACKAGE_REQUEST dont la demande a atteint
     * un état terminal (COMPLETED/CANCELLED/EXPIRED).
     *
     * @return le nombre de lignes supprimées
     */
    @Modifying
    @Query(value = """
            DELETE FROM favorites
            WHERE target_type = 'PACKAGE_REQUEST'
              AND target_id IN (
                  SELECT id FROM package_requests WHERE status IN ('COMPLETED', 'CANCELLED', 'EXPIRED')
              )
            """, nativeQuery = true)
    int deletePackageRequestFavoritesForTerminalRequests();
}
