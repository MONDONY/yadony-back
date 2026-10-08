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

    // ── Visibilité selon le type ────────────────────────────────────────────
    //
    // Toutes les requêtes « participant » ci-dessous testent le côté A par
    // « c.senderId = :userId AND c.closedAt IS NULL » : une conversation destinataire
    // fermée (V283) disparaît pour l'ancien destinataire, que porte sender_id, et reste
    // visible du voyageur en lecture seule. closed_at n'est jamais renseigné sur une
    // conversation SENDER_TRAVELER : leur comportement est inchangé.
    //
    // Les requêtes « par bid » ne visent que SENDER_TRAVELER : la conversation
    // destinataire a ses propres méthodes (RecipientConversationService).

    /** Conversation expéditeur ↔ voyageur du bid (jamais la conversation destinataire). */
    @Query("SELECT c FROM ConversationEntity c WHERE c.bidId = :bidId AND c.kind = com.yadony.api.messaging.ConversationKind.SENDER_TRAVELER")
    Optional<ConversationEntity> findByBidId(@Param("bidId") UUID bidId);

    /** Conversations destinataire encore ouvertes du bid (au plus une en régime normal). */
    @Query("SELECT c FROM ConversationEntity c WHERE c.bidId = :bidId " +
           "AND c.kind = com.yadony.api.messaging.ConversationKind.RECIPIENT_TRAVELER AND c.closedAt IS NULL")
    java.util.List<ConversationEntity> findOpenRecipientConversations(@Param("bidId") UUID bidId);

    /** Nombre de conversations destinataire (ouvertes ou fermées) du bid : sert à l'id Firestore. */
    @Query("SELECT COUNT(c) FROM ConversationEntity c WHERE c.bidId = :bidId " +
           "AND c.kind = com.yadony.api.messaging.ConversationKind.RECIPIENT_TRAVELER")
    long countRecipientConversations(@Param("bidId") UUID bidId);

    /**
     * Conversation destinataire fermée dont {@code userId} était le destinataire : sert à lui
     * répondre 404 (et non 403) quand il tente de la rouvrir.
     */
    @Query("SELECT COUNT(c) > 0 FROM ConversationEntity c WHERE c.id = :id AND c.senderId = :userId " +
           "AND c.kind = com.yadony.api.messaging.ConversationKind.RECIPIENT_TRAVELER AND c.closedAt IS NOT NULL")
    boolean existsRevokedForRecipient(@Param("id") UUID id, @Param("userId") UUID userId);

    Optional<ConversationEntity> findByFirestoreConversationId(String firestoreConversationId);

    /** Toutes les conversations d'un bid, tous types et fermées comprises (rétention des photos). */
    java.util.List<ConversationEntity> findAllByBidId(UUID bidId);

    /**
     * Le voyageur vient d'écrire dans la conversation (V301). Écriture ciblée : la colonne
     * n'est jamais réécrite par le flush d'une entité chargée avant.
     */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE ConversationEntity c SET c.travelerLastMessageAt = :at WHERE c.id = :id")
    int markTravelerMessaged(@Param("id") UUID id, @Param("at") java.time.LocalDateTime at);

    /**
     * Le voyageur a écrit au moins une fois depuis {@code since} dans une conversation du bid
     * (expéditeur ou destinataire, ouverte ou fermée) : preuve de tentative de contact.
     */
    @Query("SELECT COUNT(c) > 0 FROM ConversationEntity c WHERE c.bidId = :bidId " +
           "AND c.travelerLastMessageAt IS NOT NULL AND c.travelerLastMessageAt >= :since")
    boolean existsTravelerMessageSince(@Param("bidId") UUID bidId, @Param("since") java.time.LocalDateTime since);

    // Active conversations: not deleted AND not archived by the requesting user
    @Query("SELECT c FROM ConversationEntity c WHERE " +
           "(c.senderId = :userId AND c.closedAt IS NULL AND c.senderDeletedAt IS NULL AND c.senderArchivedAt IS NULL) OR " +
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
           "((c.senderId = :userId AND c.closedAt IS NULL AND c.senderDeletedAt IS NULL AND c.senderArchivedAt IS NULL) OR " +
           "(c.travelerId = :userId AND c.travelerDeletedAt IS NULL AND c.travelerArchivedAt IS NULL)) " +
           "AND c.senderId NOT IN :hiddenIds AND c.travelerId NOT IN :hiddenIds")
    Page<ConversationEntity> findByParticipantExcludingHidden(@Param("userId") UUID userId,
                                                              @Param("hiddenIds") Collection<UUID> hiddenIds,
                                                              Pageable pageable);

    // Archived conversations: archived but not deleted
    @Query("SELECT c FROM ConversationEntity c WHERE " +
           "(c.senderId = :userId AND c.closedAt IS NULL AND c.senderArchivedAt IS NOT NULL AND c.senderDeletedAt IS NULL) OR " +
           "(c.travelerId = :userId AND c.travelerArchivedAt IS NOT NULL AND c.travelerDeletedAt IS NULL)")
    Page<ConversationEntity> findArchivedByParticipant(@Param("userId") UUID userId, Pageable pageable);

    @Query("SELECT c FROM ConversationEntity c WHERE c.id = :id AND (" +
           "(c.senderId = :userId AND c.closedAt IS NULL AND c.senderDeletedAt IS NULL) OR " +
           "(c.travelerId = :userId AND c.travelerDeletedAt IS NULL))")
    Optional<ConversationEntity> findByIdAndParticipant(@Param("id") UUID id, @Param("userId") UUID userId);

    @Query("SELECT c FROM ConversationEntity c WHERE c.bidId = :bidId AND c.kind = com.yadony.api.messaging.ConversationKind.SENDER_TRAVELER AND (" +
           "(c.senderId = :userId AND c.closedAt IS NULL AND c.senderDeletedAt IS NULL) OR " +
           "(c.travelerId = :userId AND c.travelerDeletedAt IS NULL))")
    Optional<ConversationEntity> findByBidIdAndParticipant(
            @Param("bidId") UUID bidId, @Param("userId") UUID userId);

    // For archive/unarchive ops — visible to user (not deleted), ignores archive status
    @Query("SELECT c FROM ConversationEntity c WHERE c.id = :id AND " +
           "((c.senderId = :userId AND c.closedAt IS NULL AND c.senderDeletedAt IS NULL) OR " +
           "(c.travelerId = :userId AND c.travelerDeletedAt IS NULL))")
    Optional<ConversationEntity> findByIdAndParticipantIgnoreArchived(
            @Param("id") UUID id, @Param("userId") UUID userId);

    // Ignore-deleted variants — used for restore flows (bypass per-user visibility filter)
    @Query("SELECT c FROM ConversationEntity c WHERE c.id = :id AND ((c.senderId = :userId AND c.closedAt IS NULL) OR c.travelerId = :userId)")
    Optional<ConversationEntity> findByIdAndParticipantIgnoreDeleted(
            @Param("id") UUID id, @Param("userId") UUID userId);

    @Query("SELECT c FROM ConversationEntity c WHERE c.bidId = :bidId AND c.kind = com.yadony.api.messaging.ConversationKind.SENDER_TRAVELER AND ((c.senderId = :userId AND c.closedAt IS NULL) OR c.travelerId = :userId)")
    Optional<ConversationEntity> findByBidIdAndParticipantIgnoreDeleted(
            @Param("bidId") UUID bidId, @Param("userId") UUID userId);

    /**
     * Conversations qu'un participant voit encore : ni supprimées ni archivées de son côté.
     * Pendant compté de {@link #findByParticipant} — même clause, sans pagination.
     */
    @Query("SELECT COUNT(c) FROM ConversationEntity c WHERE " +
           "(c.senderId = :userId AND c.closedAt IS NULL AND c.senderDeletedAt IS NULL AND c.senderArchivedAt IS NULL) OR " +
           "(c.travelerId = :userId AND c.travelerDeletedAt IS NULL AND c.travelerArchivedAt IS NULL)")
    long countActiveByParticipant(@Param("userId") UUID userId);
}
