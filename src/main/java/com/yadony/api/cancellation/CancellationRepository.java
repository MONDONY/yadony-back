package com.yadony.api.cancellation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CancellationRepository extends JpaRepository<CancellationEntity, UUID> {
    List<CancellationEntity> findByCancelledBy(UUID userId);
    long countByCancelledBy(UUID userId);

    // ── Scope HANDOVER implicite — préserve le comportement et les signatures
    // existantes (aucun appelant, production ou test, n'a besoin de changer).
    // Déléguées aux versions scope-aware ci-dessous pour éviter la duplication
    // de JPQL (même filtre, même tri — HANDOVER en dur). ──

    default Optional<CancellationEntity> findByBidId(UUID bidId) {
        return findByBidIdAndScope(bidId, CancellationScope.HANDOVER);
    }

    default boolean existsByBidIdAndNoShowStatusIn(UUID bidId, List<CancellationStatus> statuses) {
        return existsByBidIdAndScopeAndNoShowStatusIn(bidId, CancellationScope.HANDOVER, statuses);
    }

    default List<CancellationEntity> findExpiredPending(OffsetDateTime now) {
        return findExpiredPendingByScope(CancellationScope.HANDOVER, now);
    }

    /** Les 2 lignes max par bid (UNIQUE(bid_id, scope)) — une seule requête pour
     *  récupérer HANDOVER et DELIVERY ensemble (voir BidService#toResponse). */
    List<CancellationEntity> findAllByBidId(UUID bidId);

    // ── Scope explicite — nouveau, utilisé par le flux DELIVERY. ──

    Optional<CancellationEntity> findByBidIdAndScope(UUID bidId, CancellationScope scope);

    boolean existsByBidIdAndScopeAndNoShowStatusIn(UUID bidId, CancellationScope scope,
                                                    List<CancellationStatus> statuses);

    @Query("SELECT c FROM CancellationEntity c WHERE c.scope = :scope " +
           "AND c.noShowStatus = 'PENDING_CONFIRMATION' AND c.contestationDeadline < :now")
    List<CancellationEntity> findExpiredPendingByScope(@Param("scope") CancellationScope scope,
                                                        @Param("now") OffsetDateTime now);

    /** File admin des no-shows (GET /admin/cancellations) : motifs, portées et statuts explicites. */
    @Query("SELECT c FROM CancellationEntity c WHERE c.reason IN :reasons " +
           "AND c.scope IN :scopes AND c.noShowStatus IN :statuses")
    Page<CancellationEntity> findAdminNoShows(@Param("reasons") Collection<String> reasons,
                                              @Param("scopes") Collection<CancellationScope> scopes,
                                              @Param("statuses") Collection<CancellationStatus> statuses,
                                              Pageable pageable);
}
