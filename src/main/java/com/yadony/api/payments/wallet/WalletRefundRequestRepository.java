package com.yadony.api.payments.wallet;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletRefundRequestRepository extends JpaRepository<WalletRefundRequestEntity, UUID> {

    Optional<WalletRefundRequestEntity> findByUserIdAndCurrencyAndStatus(
            UUID userId, String currency, WalletRefundRequestStatus status);

    Optional<WalletRefundRequestEntity> findByUserIdAndCurrencyAndStatusIn(
            UUID userId, String currency, List<WalletRefundRequestStatus> statuses);

    boolean existsByUserIdAndCurrencyAndStatusIn(
            UUID userId, String currency, List<WalletRefundRequestStatus> statuses);

    List<WalletRefundRequestEntity> findAllByUserIdAndStatus(UUID userId, WalletRefundRequestStatus status);

    List<WalletRefundRequestEntity> findAllByUserIdOrderByRequestedAtDesc(UUID userId);

    Page<WalletRefundRequestEntity> findAllByStatusOrderByRequestedAtAsc(
            WalletRefundRequestStatus status, Pageable pageable);

    /** File admin filtrée par statut ; l'ordre vient du {@link Pageable}. */
    Page<WalletRefundRequestEntity> findAllByStatusIn(
            java.util.Collection<WalletRefundRequestStatus> statuses, Pageable pageable);

    /**
     * File admin cherchée : identifiant de la demande ou de l'utilisateur, ou nom de
     * l'utilisateur, comptes supprimés compris (requête native, hors {@code @Where}).
     */
    @Query(value = """
            SELECT r.* FROM wallet_refund_requests r JOIN users u ON u.id = r.user_id
            WHERE r.deleted_at IS NULL AND r.status IN (:statuses)
              AND ((CAST(:id AS VARCHAR) IS NOT NULL AND (r.id = CAST(:id AS UUID) OR r.user_id = CAST(:id AS UUID)))
                   OR (CAST(:like AS VARCHAR) IS NOT NULL
                       AND LOWER(CONCAT(COALESCE(u.first_name, ''), ' ', COALESCE(u.last_name, ''))) LIKE :like))
            ORDER BY r.requested_at DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM wallet_refund_requests r JOIN users u ON u.id = r.user_id
            WHERE r.deleted_at IS NULL AND r.status IN (:statuses)
              AND ((CAST(:id AS VARCHAR) IS NOT NULL AND (r.id = CAST(:id AS UUID) OR r.user_id = CAST(:id AS UUID)))
                   OR (CAST(:like AS VARCHAR) IS NOT NULL
                       AND LOWER(CONCAT(COALESCE(u.first_name, ''), ' ', COALESCE(u.last_name, ''))) LIKE :like))
            """,
            nativeQuery = true)
    Page<WalletRefundRequestEntity> searchAdmin(@Param("statuses") java.util.Collection<String> statuses,
                                                @Param("id") String id,
                                                @Param("like") String like,
                                                Pageable pageable);

    boolean existsByParentRequestId(UUID parentRequestId);

    /** Verrou de la demande : sérialise l'écouteur après commit et la reprise planifiée. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM WalletRefundRequestEntity r WHERE r.id = :id")
    Optional<WalletRefundRequestEntity> findByIdForUpdate(@Param("id") UUID id);
}
