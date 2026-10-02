package com.yadony.api.calls;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CallRepository extends JpaRepository<CallEntity, UUID> {
    Optional<CallEntity> findByStreamCallId(String streamCallId);
    boolean existsByConversationIdAndStatusIn(UUID conversationId, Collection<CallStatus> statuses);
    List<CallEntity> findByBidIdOrderByCreatedAtDesc(UUID bidId);

    /** Appels encore en cours, pour l'expiration des lignes que Stream n'a jamais soldées. */
    List<CallEntity> findByStatusIn(Collection<CallStatus> statuses);

    List<CallEntity> findByConversationIdAndStatusIn(UUID conversationId, Collection<CallStatus> statuses);

    /** RINGING → ANSWERED, atomique : 0 si l'appel n'est plus en sonnerie. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update CallEntity c set c.status = com.yadony.api.calls.CallStatus.ANSWERED, c.startedAt = :at "
            + "where c.id = :id and c.status = com.yadony.api.calls.CallStatus.RINGING")
    int markAnswered(@Param("id") UUID id, @Param("at") OffsetDateTime at);

    /**
     * Passe un appel en cours à un statut terminal, atomique : 0 s'il l'était déjà. Deux webhooks
     * concurrents ne peuvent donc pas publier deux fins pour le même appel.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update CallEntity c set c.status = :status, c.endedAt = :at, "
            + "c.startedAt = coalesce(c.startedAt, :startedAt), c.durationSeconds = :duration "
            + "where c.id = :id and c.status in (com.yadony.api.calls.CallStatus.RINGING, com.yadony.api.calls.CallStatus.ANSWERED)")
    int finishIfLive(@Param("id") UUID id, @Param("status") CallStatus status, @Param("at") OffsetDateTime at,
                     @Param("startedAt") OffsetDateTime startedAt, @Param("duration") Integer duration);

    /** Appel que Stream n'a pas pu créer : écarté (soft delete), il ne compte plus comme en cours. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update CallEntity c set c.deletedAt = :at where c.id = :id and c.deletedAt is null")
    int discard(@Param("id") UUID id, @Param("at") LocalDateTime at);
}
