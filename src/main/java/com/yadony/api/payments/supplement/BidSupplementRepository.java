package com.yadony.api.payments.supplement;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BidSupplementRepository extends JpaRepository<BidSupplementEntity, UUID> {

    /** Suppléments d'un colis, du plus récent au plus ancien. */
    List<BidSupplementEntity> findByBidIdOrderByCreatedAtDesc(UUID bidId);

    /** Préchargement des listes de colis ({@code BidResponsePrefetch}) : une requête par lot. */
    List<BidSupplementEntity> findByBidIdIn(Collection<UUID> bidIds);

    @Query("SELECT s.bidId FROM BidSupplementEntity s WHERE s.id = :id")
    Optional<UUID> findBidIdById(@Param("id") UUID id);

    /**
     * Supplément d'un colis, verrouillé. Toujours APRÈS le verrou du colis
     * ({@code BidRepository.lockForUpdate}) : ordre « colis puis paiement » de {@code PaymentService}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM BidSupplementEntity s WHERE s.id = :id AND s.bidId = :bidId")
    Optional<BidSupplementEntity> findByIdAndBidIdForUpdate(@Param("id") UUID id, @Param("bidId") UUID bidId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM BidSupplementEntity s WHERE s.bidId = :bidId AND s.status IN :statuses")
    Optional<BidSupplementEntity> findByBidIdAndStatusInForUpdate(@Param("bidId") UUID bidId,
                                                                  @Param("statuses") Collection<BidSupplementStatus> statuses);

    boolean existsByBidIdAndStatusIn(UUID bidId, Collection<BidSupplementStatus> statuses);

    long countByBidId(UUID bidId);

    /** Filet : demandes encore ouvertes dont l'échéance est passée. */
    @Query("SELECT s.id FROM BidSupplementEntity s WHERE s.status IN :statuses AND s.expiresAt < :now ORDER BY s.expiresAt")
    List<UUID> findIdsOpenExpiredBefore(@Param("statuses") Collection<BidSupplementStatus> statuses,
                                        @Param("now") LocalDateTime now, Pageable page);

    /**
     * Filet : colis morts sans livraison (statut stable depuis {@code before}) qui gardent une
     * demande dans l'un des {@code statuses}.
     */
    @Query("SELECT DISTINCT s.bidId FROM BidSupplementEntity s, com.yadony.api.matching.BidEntity b "
            + "WHERE b.id = s.bidId AND s.status IN :statuses AND b.status IN :bidStatuses AND b.updatedAt < :before")
    List<UUID> findBidIdsEndedWithSupplementIn(@Param("statuses") Collection<BidSupplementStatus> statuses,
                                               @Param("bidStatuses") Collection<com.yadony.api.matching.BidStatus> bidStatuses,
                                               @Param("before") LocalDateTime before, Pageable page);

    /** Rapprochement R2 : clés photo sous {@code prefix}, suppléments supprimés logiquement compris. */
    @Query(value = "SELECT photo_key FROM bid_supplements WHERE photo_key LIKE CONCAT(:prefix, '%')", nativeQuery = true)
    List<String> findPhotoKeysUnderIncludingDeleted(@Param("prefix") String prefix);
}
