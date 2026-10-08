package com.yadony.api.messaging;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessagingImageRepository extends JpaRepository<MessagingImageEntity, UUID> {

    Optional<MessagingImageEntity> findByConversationIdAndFirestoreMessageId(UUID conversationId,
                                                                             String firestoreMessageId);

    /** Photos d'une conversation, purgées comprises (lecture admin, purge de la conversation). */
    List<MessagingImageEntity> findByConversationId(UUID conversationId);

    /** Photos encore présentes d'un bid, toutes conversations confondues (expéditeur et destinataire). */
    @Query("SELECT i FROM MessagingImageEntity i WHERE i.bidId = :bidId AND i.purgedAt IS NULL")
    List<MessagingImageEntity> findLiveByBidId(@Param("bidId") UUID bidId);

    /** Bids ayant au moins une photo encore présente : point d'entrée de la purge planifiée. */
    @Query("SELECT DISTINCT i.bidId FROM MessagingImageEntity i WHERE i.purgedAt IS NULL")
    List<UUID> findBidIdsWithLiveImages();
}
