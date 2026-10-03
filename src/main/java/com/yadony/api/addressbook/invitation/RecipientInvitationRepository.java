package com.yadony.api.addressbook.invitation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface RecipientInvitationRepository extends JpaRepository<RecipientInvitationEntity, UUID> {

    long countByInviterUserIdAndCreatedAtAfter(UUID inviterUserId, LocalDateTime since);

    boolean existsByInviterUserIdAndTargetHashAndStatusIn(UUID inviterUserId, String targetHash,
                                                          Collection<InvitationStatus> statuses);

    boolean existsByInviterUserIdAndInviteeUserIdAndStatus(UUID inviterUserId, UUID inviteeUserId,
                                                           InvitationStatus status);

    List<RecipientInvitationEntity> findByInviteeUserIdIsNullAndStatusAndTargetHashIn(
            InvitationStatus status, Collection<String> targetHashes);

    List<RecipientInvitationEntity> findByInviteeUserIdAndStatusInOrderByCreatedAtDesc(
            UUID inviteeUserId, Collection<InvitationStatus> statuses);

    List<RecipientInvitationEntity> findByInviterUserIdAndStatusInOrderByCreatedAtDesc(
            UUID inviterUserId, Collection<InvitationStatus> statuses);

    List<RecipientInvitationEntity> findByInviterUserIdAndRecipientIdAndStatus(
            UUID inviterUserId, UUID recipientId, InvitationStatus status);

    /** Entrées du carnet de {@code inviterUserId} liées à un compte Yadony par une invitation acceptée. */
    @Query("select i.recipientId from RecipientInvitationEntity i where i.inviterUserId = :inviterUserId "
            + "and i.status = com.yadony.api.addressbook.invitation.InvitationStatus.ACCEPTED "
            + "and i.recipientId is not null")
    Set<UUID> findLinkedRecipientIds(@Param("inviterUserId") UUID inviterUserId);
}
