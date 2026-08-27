package com.yadony.api.billing;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.UUID;

/**
 * Abonnement PRO d'un voyageur. Au plus une ligne vivante par utilisateur
 * (index unique partiel {@code uq_pro_subscriptions_user}).
 *
 * <p>Cette entité est la source de vérité ; {@code UserEntity.isProAccount}
 * en est la projection, maintenue par {@link ProAccessSynchronizer}.
 */
@Entity
@Table(name = "pro_subscriptions")
@SQLRestriction("deleted_at IS NULL")
public class ProSubscriptionEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ProSubscriptionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private ProSubscriptionSource source;

    @Column(name = "stripe_customer_id", length = 255)
    private String stripeCustomerId;

    @Column(name = "stripe_subscription_id", length = 255)
    private String stripeSubscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_cycle", length = 8)
    private BillingCycle billingCycle;

    @Column(name = "current_period_end")
    private Instant currentPeriodEnd;

    @Column(name = "cancel_at_period_end", nullable = false)
    private boolean cancelAtPeriodEnd = false;

    /** Échéance de la grâce accordée à la cohorte LEGACY_FREE. */
    @Column(name = "grace_expires_at")
    private Instant graceExpiresAt;

    /**
     * Horodatage d'entrée en PAST_DUE, remis à {@code null} à la sortie.
     * {@code updatedAt} ne conviendrait pas : toute écriture sur la ligne
     * le repousserait et le dunning ne finirait jamais.
     */
    @Column(name = "past_due_since")
    private Instant pastDueSince;

    @Column(name = "granted_by_admin_id")
    private UUID grantedByAdminId;

    @Column(name = "admin_grant_reason", length = 500)
    private String adminGrantReason;

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public ProSubscriptionStatus getStatus() { return status; }
    public void setStatus(ProSubscriptionStatus status) { this.status = status; }

    public ProSubscriptionSource getSource() { return source; }
    public void setSource(ProSubscriptionSource source) { this.source = source; }

    public String getStripeCustomerId() { return stripeCustomerId; }
    public void setStripeCustomerId(String stripeCustomerId) { this.stripeCustomerId = stripeCustomerId; }

    public String getStripeSubscriptionId() { return stripeSubscriptionId; }
    public void setStripeSubscriptionId(String stripeSubscriptionId) { this.stripeSubscriptionId = stripeSubscriptionId; }

    public BillingCycle getBillingCycle() { return billingCycle; }
    public void setBillingCycle(BillingCycle billingCycle) { this.billingCycle = billingCycle; }

    public Instant getCurrentPeriodEnd() { return currentPeriodEnd; }
    public void setCurrentPeriodEnd(Instant currentPeriodEnd) { this.currentPeriodEnd = currentPeriodEnd; }

    public boolean isCancelAtPeriodEnd() { return cancelAtPeriodEnd; }
    public void setCancelAtPeriodEnd(boolean cancelAtPeriodEnd) { this.cancelAtPeriodEnd = cancelAtPeriodEnd; }

    public Instant getGraceExpiresAt() { return graceExpiresAt; }
    public void setGraceExpiresAt(Instant graceExpiresAt) { this.graceExpiresAt = graceExpiresAt; }

    public Instant getPastDueSince() { return pastDueSince; }
    public void setPastDueSince(Instant pastDueSince) { this.pastDueSince = pastDueSince; }

    public UUID getGrantedByAdminId() { return grantedByAdminId; }
    public void setGrantedByAdminId(UUID grantedByAdminId) { this.grantedByAdminId = grantedByAdminId; }

    public String getAdminGrantReason() { return adminGrantReason; }
    public void setAdminGrantReason(String adminGrantReason) { this.adminGrantReason = adminGrantReason; }
}
