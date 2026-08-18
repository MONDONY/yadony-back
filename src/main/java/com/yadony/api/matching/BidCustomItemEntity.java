package com.yadony.api.matching;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Ligne hors grille d'un bid en négociation : le voyageur n'a jamais tarifé cet
 * article, c'est donc l'expéditeur qui le décrit et le chiffre.
 * {@code amountEur} est un montant UNITAIRE.
 */
@Entity
@Table(name = "bid_custom_items")
public class BidCustomItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "bid_id", nullable = false)
    private UUID bidId;

    @Column(name = "label", nullable = false, length = 100)
    private String label;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "amount_eur", nullable = false, precision = 10, scale = 2)
    private BigDecimal amountEur;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(ZoneOffset.UTC); }

    public UUID getId() { return id; }
    public UUID getBidId() { return bidId; }
    public void setBidId(UUID bidId) { this.bidId = bidId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public BigDecimal getAmountEur() { return amountEur; }
    public void setAmountEur(BigDecimal amountEur) { this.amountEur = amountEur; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
