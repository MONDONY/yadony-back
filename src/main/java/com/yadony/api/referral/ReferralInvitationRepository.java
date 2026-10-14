package com.yadony.api.referral;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface ReferralInvitationRepository extends JpaRepository<ReferralInvitationEntity, UUID> {

    Page<ReferralInvitationEntity> findByReferrerUserIdOrderByCreatedAtDesc(UUID referrerUserId, Pageable pageable);

    Optional<ReferralInvitationEntity> findByRefereeUserIdAndStatus(UUID refereeUserId, String status);

    /**
     * Rend la récompense disponible si l'invitation est encore {@code SIGNED_UP}. Renvoie 1
     * pour l'appel qui la fait passer, 0 pour un appel concurrent ou rejoué : c'est ce
     * retour, et non la lecture préalable, qui décide de l'audit et de la notification.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE ReferralInvitationEntity i SET i.status = 'FIRST_DELIVERY', "
            + "i.rewardStatus = com.yadony.api.referral.ReferralRewardStatus.AVAILABLE, "
            + "i.rewardAvailableAt = :at, i.updatedAt = :at "
            + "WHERE i.id = :id AND i.status = 'SIGNED_UP'")
    int markRewardAvailableIfSignedUp(@Param("id") UUID id, @Param("at") LocalDateTime at);

    /** Verrou du choix de récompense : un seul choix par invitation, même en double clic. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM ReferralInvitationEntity i WHERE i.id = :id")
    Optional<ReferralInvitationEntity> findByIdForUpdate(@Param("id") UUID id);

    /** Le compte a-t-il déjà été parrainé (quel que soit l'avancement de l'invitation) ? */
    boolean existsByRefereeUserId(UUID refereeUserId);

    long countByReferrerUserId(UUID referrerUserId);

    long countByReferrerUserIdAndRefereeUserIdIsNotNull(UUID referrerUserId);

    long countByReferrerUserIdAndStatusIn(UUID referrerUserId, Collection<String> statuses);

    long countByReferrerUserIdAndRewardStatus(UUID referrerUserId, ReferralRewardStatus rewardStatus);
}
