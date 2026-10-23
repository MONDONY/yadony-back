package com.yadony.api.payments.amendment;

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
 * Article de grille ajouté par une modification de colis (V347) : instantané du libellé et du prix net
 * au moment de la demande, comme {@code BidGridItemEntity} pour le colis principal. Immuable, donc sans
 * {@code updated_at} ni suppression logique.
 */
@Entity
@Table(name = "bid_amendment_items")
public class BidAmendmentItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "amendment_id", nullable = false, updatable = false)
    private UUID amendmentId;

    @Column(name = "announcement_grid_item_id", nullable = false, updatable = false)
    private UUID announcementGridItemId;

    @Column(name = "label_snapshot", nullable = false, length = 100, updatable = false)
    private String labelSnapshot;

    @Column(name = "unit_price_net_snapshot", nullable = false, precision = 10, scale = 2, updatable = false)
    private BigDecimal unitPriceNetSnapshot;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    public UUID getId() { return id; }
    public UUID getAmendmentId() { return amendmentId; }
    public void setAmendmentId(UUID amendmentId) { this.amendmentId = amendmentId; }
    public UUID getAnnouncementGridItemId() { return announcementGridItemId; }
    public void setAnnouncementGridItemId(UUID announcementGridItemId) { this.announcementGridItemId = announcementGridItemId; }
    public String getLabelSnapshot() { return labelSnapshot; }
    public void setLabelSnapshot(String labelSnapshot) { this.labelSnapshot = labelSnapshot; }
    public BigDecimal getUnitPriceNetSnapshot() { return unitPriceNetSnapshot; }
    public void setUnitPriceNetSnapshot(BigDecimal unitPriceNetSnapshot) { this.unitPriceNetSnapshot = unitPriceNetSnapshot; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
