package com.yadony.api.referral;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Tracks referral invitations: from a referrer to a referee.
 *
 * <p>Statut ({@code status}) : {@code SIGNED_UP} (code utilisé à l'inscription) →
 * {@code FIRST_DELIVERY} (premier envoi du filleul livré, récompense à choisir) →
 * {@code REWARDED} (le parrain a reçu sa récompense). {@code PENDING} n'est jamais écrit.
 * Les lignes {@code REWARDED} d'avant V321 ont reçu un bon -50 % sans passer par
 * {@code FIRST_DELIVERY}.
 *
 * <p>La récompense elle-même est suivie par {@link #rewardStatus} / {@link #rewardChoice}.
 */
@Entity
@Table(name = "referral_invitations")
@Where(clause = "deleted_at IS NULL")
public class ReferralInvitationEntity extends BaseEntity {

    @Column(name = "referrer_user_id", nullable = false)
    private UUID referrerUserId;

    @Column(name = "referee_user_id")
    private UUID refereeUserId;

    @Column(name = "referee_phone", length = 20)
    private String refereePhone;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "code_used", length = 20)
    private String codeUsed;

    @Column(name = "rewarded_at")
    private LocalDateTime rewardedAt;

    @Column(name = "credit_amount_cents")
    private Integer creditAmountCents;

    @Enumerated(EnumType.STRING)
    @Column(name = "reward_status", nullable = false, length = 20)
    private ReferralRewardStatus rewardStatus = ReferralRewardStatus.NONE;

    @Enumerated(EnumType.STRING)
    @Column(name = "reward_choice", length = 30)
    private ReferralRewardChoice rewardChoice;

    @Column(name = "reward_available_at")
    private LocalDateTime rewardAvailableAt;

    @Column(name = "reward_chosen_at")
    private LocalDateTime rewardChosenAt;

    /** Code promo personnel créé pour le choix {@link ReferralRewardChoice#DISCOUNT_CODE}. */
    @Column(name = "reward_promo_code_id")
    private UUID rewardPromoCodeId;

    public UUID getReferrerUserId() { return referrerUserId; }
    public void setReferrerUserId(UUID referrerUserId) { this.referrerUserId = referrerUserId; }

    public UUID getRefereeUserId() { return refereeUserId; }
    public void setRefereeUserId(UUID refereeUserId) { this.refereeUserId = refereeUserId; }

    public String getRefereePhone() { return refereePhone; }
    public void setRefereePhone(String refereePhone) { this.refereePhone = refereePhone; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCodeUsed() { return codeUsed; }
    public void setCodeUsed(String codeUsed) { this.codeUsed = codeUsed; }

    public LocalDateTime getRewardedAt() { return rewardedAt; }
    public void setRewardedAt(LocalDateTime rewardedAt) { this.rewardedAt = rewardedAt; }

    public Integer getCreditAmountCents() { return creditAmountCents; }
    public void setCreditAmountCents(Integer creditAmountCents) { this.creditAmountCents = creditAmountCents; }

    public ReferralRewardStatus getRewardStatus() { return rewardStatus; }
    public void setRewardStatus(ReferralRewardStatus rewardStatus) { this.rewardStatus = rewardStatus; }

    public ReferralRewardChoice getRewardChoice() { return rewardChoice; }
    public void setRewardChoice(ReferralRewardChoice rewardChoice) { this.rewardChoice = rewardChoice; }

    public LocalDateTime getRewardAvailableAt() { return rewardAvailableAt; }
    public void setRewardAvailableAt(LocalDateTime rewardAvailableAt) { this.rewardAvailableAt = rewardAvailableAt; }

    public LocalDateTime getRewardChosenAt() { return rewardChosenAt; }
    public void setRewardChosenAt(LocalDateTime rewardChosenAt) { this.rewardChosenAt = rewardChosenAt; }

    public UUID getRewardPromoCodeId() { return rewardPromoCodeId; }
    public void setRewardPromoCodeId(UUID rewardPromoCodeId) { this.rewardPromoCodeId = rewardPromoCodeId; }
}
