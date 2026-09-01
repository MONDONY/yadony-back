package com.yadony.api.messaging;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface ConversationRepository extends JpaRepository<ConversationEntity, UUID> {

    // Raw lookup by bid — no visibility filter (used internally for creation checks)
    Optional<ConversationEntity> findByBidId(UUID bidId);

    Optional<ConversationEntity> findByFirestoreConversationId(String firestoreConversationId);

    // Active conversations: not deleted AND not archived by the requesting user
    @Query("SELECT c FROM ConversationEntity c WHERE " +
           "(c.senderId = :userId AND c.senderDeletedAt IS NULL AND c.senderArchivedAt IS NULL) OR " +
           "(c.travelerId = :userId AND c.travelerDeletedAt IS NULL AND c.travelerArchivedAt IS NULL)")
    Page<ConversationEntity> findByParticipant(@Param("userId") UUID userId, Pageable pageable);

    /**
     * Même liste que {@link #findByParticipant}, amputée des fils dont la contrepartie est
     * masquée (blocage sans transaction en cours).
     *
     * <p>Le filtrage est en base et non après pagination : retirer des lignes d'une page
     * déjà constituée rendrait des pages courtes et un total faux. Les deux colonnes sont
     * testées car {@code hiddenIds} ne contient jamais l'appelant lui-même, personne ne
     * pouvant se bloquer soi-même.
     *
     * <p>À n'appeler qu'avec une collection non vide : un {@code NOT IN ()} vide n'a pas
     * de sens en JPQL. Sinon, {@link #findByParticipant}.
     */
    @Query("SELECT c FROM ConversationEntity c WHERE " +
           "((c.senderId = :userId AND c.senderDeletedAt IS NULL AND c.senderArchivedAt IS NULL) OR " +
           "(c.travelerId = :userId AND c.travelerDeletedAt IS NULL AND c.travelerArchivedAt IS NULL)) " +
           "AND c.senderId NOT IN :hiddenIds AND c.travelerId NOT IN :hiddenIds")
    Page<ConversationEntity> findByParticipantExcludingHidden(@Param("userId") UUID userId,
                                                              @Param("hiddenIds") Collection<UUID> hiddenIds,
                                                              Pageable pageable);

    // Archived conversations: archived but not deleted
    @Query("SELECT c FROM ConversationEntity c WHERE " +
           "(c.senderId = :userId AND c.senderArchivedAt IS NOT NULL AND c.senderDeletedAt IS NULL) OR " +
           "(c.travelerId = :userId AND c.travelerArchivedAt IS NOT NULL AND c.travelerDeletedAt IS NULL)")
    Page<ConversationEntity> findArchivedByParticipant(@Param("userId") UUID userId, Pageable pageable);

    @Query("SELECT c FROM ConversationEntity c WHERE c.id = :id AND (" +
           "(c.senderId = :userId AND c.senderDeletedAt IS NULL) OR " +
           "(c.travelerId = :userId AND c.travelerDeletedAt IS NULL))")
    Optional<ConversationEntity> findByIdAndParticipant(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query("SELECT c FROM ConversationEntity c WHERE c.bidId = :bidId AND (" +
           "(c.senderId = :userId AND c.senderDeletedAt IS NULL) OR " +
           "(c.travelerId = :userId AND c.travelerDeletedAt IS NULL))")
    Optional<ConversationEntity> findByBidIdAndParticipant(
            @Param("bidId") UUID bidId, @Param("userId") UUID userId);

    // For archive/unarchive ops — visible to user (not deleted), ignores archive status
    @Query("SELECT c FROM ConversationEntity c WHERE c.id = :id AND " +
           "((c.senderId = :userId AND c.senderDeletedAt IS NULL) OR " +
           "(c.travelerId = :userId AND c.travelerDeletedAt IS NULL))")
    Optional<ConversationEntity> findByIdAndParticipantIgnoreArchived(
            @Param("id") UUID id, @Param("userId") UUID userId);

    // Ignore-deleted variants — used for restore flows (bypass per-user visibility filter)
    @Query("SELECT c FROM ConversationEntity c WHERE c.id = :id AND (c.senderId = :userId OR c.travelerId = :userId)")
    Optional<ConversationEntity> findByIdAndParticipantIgnoreDeleted(
            @Param("id") UUID id, @Param("userId") UUID userId);

    @Query("SELECT c FROM ConversationEntity c WHERE c.bidId = :bidId AND (c.senderId = :userId OR c.travelerId = :userId)")
    Optional<ConversationEntity> findByBidIdAndParticipantIgnoreDeleted(
            @Param("bidId") UUID bidId, @Param("userId") UUID userId);

    /**
     * Conversations qu'un participant voit encore : ni supprimées ni archivées de son côté.
     * Pendant compté de {@link #findByParticipant} — même clause, sans pagination.
     */
    @Query("SELECT COUNT(c) FROM ConversationEntity c WHERE " +
           "(c.senderId = :userId AND c.senderDeletedAt IS NULL AND c.senderArchivedAt IS NULL) OR " +
           "(c.travelerId = :userId AND c.travelerDeletedAt IS NULL AND c.travelerArchivedAt IS NULL)")
    long countActiveByParticipant(@Param("userId") UUID userId);
}
