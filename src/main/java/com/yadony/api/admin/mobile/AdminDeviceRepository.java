package com.yadony.api.admin.mobile;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminDeviceRepository extends JpaRepository<AdminDeviceEntity, UUID> {

    /** Appareils actifs (le filtre {@code @Where} écarte les supprimés). */
    @Query("select d from AdminDeviceEntity d where d.adminId = :adminId order by d.createdAt")
    List<AdminDeviceEntity> findActiveByAdminId(@Param("adminId") UUID adminId);

    @Query("select d from AdminDeviceEntity d order by d.createdAt")
    List<AdminDeviceEntity> findActive();

    /**
     * Cherche par jeton, supprimés compris (SQL natif : {@code @Where} ne s'y applique pas). Le
     * jeton est unique en base : un appareil supprimé puis ré-enregistré doit être repris.
     */
    @Query(value = "select * from admin_devices where fcm_token = :token", nativeQuery = true)
    Optional<AdminDeviceEntity> findAnyByFcmToken(@Param("token") String token);

    /**
     * Avance le curseur du répartiteur, jamais vers l'arrière. UPDATE ciblé plutôt qu'un
     * {@code save} de l'entité lue en début de passage (BaseEntity n'a pas de {@code @Version}) :
     * un désabonnement ou une reprise du jeton par un autre admin, survenus entre-temps, ne sont
     * pas écrasés. Rend 0 si l'appareil a été supprimé, réattribué ou déjà avancé plus loin.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AdminDeviceEntity d set d.lastPushedAt = :at where d.id = :id and d.adminId = :adminId "
            + "and d.deletedAt is null and d.lastPushedAt < :at")
    int advanceLastPushedAt(@Param("id") UUID id, @Param("adminId") UUID adminId, @Param("at") Instant at);

    /** Suppression logique ciblée d'un appareil encore actif ; rend 0 s'il l'était déjà. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AdminDeviceEntity d set d.deletedAt = :now, d.updatedAt = :now "
            + "where d.id = :id and d.deletedAt is null")
    int softDeleteActive(@Param("id") UUID id, @Param("now") LocalDateTime now);
}
