package com.yadony.api.voucher;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface CommissionVoucherRepository extends JpaRepository<CommissionVoucherEntity, UUID> {

    Optional<CommissionVoucherEntity> findBySourceInvitationId(UUID sourceInvitationId);

    // Scopé par userId : un même bid peut porter DEUX consommations distinctes (le bon
    // de l'expéditeur ET celui du voyageur), (bidId) seul les confondrait.
    Optional<CommissionVoucherEntity> findByConsumedOnBidIdAndUserId(UUID bidId, UUID userId);

    /** Le plus ancien bon encore disponible pour ce détenteur (FIFO — règle "un bon = une transaction"). */
    @Query("SELECT v FROM CommissionVoucherEntity v "
            + "WHERE v.userId = :userId AND v.consumedAt IS NULL AND v.expiresAt > :now "
            + "ORDER BY v.grantedAt ASC")
    java.util.List<CommissionVoucherEntity> findActiveByUserId(
            @Param("userId") UUID userId, @Param("now") LocalDateTime now);

    /** Verrou pessimiste pour la consommation atomique (pas de lecture puis écriture séparée). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT v FROM CommissionVoucherEntity v WHERE v.id = :id")
    Optional<CommissionVoucherEntity> findByIdForUpdate(@Param("id") UUID id);
}
