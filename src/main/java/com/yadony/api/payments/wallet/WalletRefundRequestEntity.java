package com.yadony.api.payments.wallet;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Demande de remboursement d'un solde wallet rechargé par carte, ouverte automatiquement à
 * la suppression de compte (cf. {@code UserService#openWalletRefundTicketIfNeeded}, jamais
 * bloquant) ou explicitement en dehors de toute suppression — aucun flow de remboursement
 * automatique n'existe (cf. {@link WalletRefundRequestService}), un admin rembourse
 * manuellement via Stripe puis résout le ticket.
 */
@Entity
@Table(name = "wallet_refund_requests")
@Where(clause = "deleted_at IS NULL")
public class WalletRefundRequestEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** Solde au moment de la demande — informatif pour l'admin (montant à rembourser
     *  via Stripe). La résolution débite le solde RÉEL au moment du clic, pas ce
     *  snapshot, au cas où il ait légèrement bougé entre-temps. */
    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "status", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private WalletRefundRequestStatus status = WalletRefundRequestStatus.PENDING;

    @Column(name = "channel", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private WalletRefundChannel channel = WalletRefundChannel.MANUAL_ADMIN;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public WalletRefundRequestStatus getStatus() { return status; }
    public void setStatus(WalletRefundRequestStatus status) { this.status = status; }

    public WalletRefundChannel getChannel() { return channel; }
    public void setChannel(WalletRefundChannel channel) { this.channel = channel; }

    public LocalDateTime getRequestedAt() { return requestedAt; }
    public void setRequestedAt(LocalDateTime requestedAt) { this.requestedAt = requestedAt; }

    public LocalDateTime getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(LocalDateTime resolvedAt) { this.resolvedAt = resolvedAt; }

    public UUID getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(UUID resolvedBy) { this.resolvedBy = resolvedBy; }
}
