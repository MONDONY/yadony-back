package com.yadony.api.admin.notifications;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

public interface AdminNotificationReadRepository extends JpaRepository<AdminNotificationReadEntity, UUID> {

    boolean existsByAdminIdAndNotificationId(UUID adminId, String notificationId);

    /**
     * Notifications que cet administrateur a lues depuis {@code since}. Une notification de la
     * fenêtre du fil a été lue après sa création : borner par la date de lecture suffit.
     */
    @Query("SELECT r.notificationId FROM AdminNotificationReadEntity r "
            + "WHERE r.adminId = :adminId AND r.readAt >= :since")
    Set<String> findReadIds(@Param("adminId") UUID adminId, @Param("since") LocalDateTime since);
}
