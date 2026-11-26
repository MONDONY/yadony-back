package com.yadony.api.admin.mobile;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
