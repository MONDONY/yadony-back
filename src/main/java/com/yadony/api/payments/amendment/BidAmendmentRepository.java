package com.yadony.api.payments.amendment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BidAmendmentRepository extends JpaRepository<BidAmendmentEntity, UUID> {

    /**
     * Modification d'un colis, verrouillée. Toujours APRÈS le verrou du colis
     * ({@code BidRepository.lockForUpdate}) : ordre « colis puis paiement » de {@code PaymentService}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM BidAmendmentEntity a WHERE a.id = :id AND a.bidId = :bidId")
    Optional<BidAmendmentEntity> findByIdAndBidIdForUpdate(@Param("id") UUID id, @Param("bidId") UUID bidId);

    Optional<BidAmendmentEntity> findFirstByBidIdAndStatusIn(UUID bidId, Collection<BidAmendmentStatus> statuses);

    /** Modifications d'un colis, de la plus récente à la plus ancienne. */
    List<BidAmendmentEntity> findByBidIdOrderByCreatedAtDesc(UUID bidId);

    /** Modifications acceptées du colis (plafond {@code max-accepted-per-bid}), remboursées comprises. */
    long countByBidIdAndAcceptedAtNotNull(UUID bidId);

    /**
     * Somme des nets des modifications du colis dans l'un des {@code statuses} (0 si aucune). Liste
     * positive : une modification close après acceptation ({@code CANCELLED}, {@code EXPIRED}...) ou
     * remboursée n'est jamais comptée, même avec {@code accepted_at} renseigné.
     */
    @Query("SELECT COALESCE(SUM(a.netAmount), 0) FROM BidAmendmentEntity a "
            + "WHERE a.bidId = :bidId AND a.status IN :statuses")
    java.math.BigDecimal sumNetByBidIdAndStatusIn(@Param("bidId") UUID bidId,
                                                  @Param("statuses") Collection<BidAmendmentStatus> statuses);

    /** Préchargement des listes de colis : une requête par lot. */
    List<BidAmendmentEntity> findByBidIdIn(Collection<UUID> bidIds);

    /** Filet : modifications encore dans l'un des {@code statuses} dont l'échéance est passée. */
    @Query("SELECT a.id FROM BidAmendmentEntity a WHERE a.status IN :statuses AND a.expiresAt < :now "
            + "ORDER BY a.expiresAt")
    List<UUID> findIdsByStatusInAndExpiresAtBefore(@Param("statuses") Collection<BidAmendmentStatus> statuses,
                                                   @Param("now") LocalDateTime now);
}
