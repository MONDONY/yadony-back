package com.yadony.api.payments.currency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Taux de change pilotable par l'admin, une ligne par devise supportée.
 *
 * <p>N'étend pas {@code BaseEntity} : la clé métier est le code devise (pas un
 * UUID généré), et une table de référence n'a ni soft delete ni cycle de vie
 * applicatif propre, contrairement aux entités métier du reste du projet.
 */
@Entity
@Table(name = "exchange_rates")
public class ExchangeRateEntity {

    @Id
    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "units_per_eur", nullable = false, precision = 18, scale = 6)
    private BigDecimal unitsPerEur;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    protected ExchangeRateEntity() {
        // JPA
    }

    public ExchangeRateEntity(String currency, BigDecimal unitsPerEur) {
        this.currency = currency;
        this.unitsPerEur = unitsPerEur;
        this.updatedAt = OffsetDateTime.now();
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getUnitsPerEur() {
        return unitsPerEur;
    }

    public void setUnitsPerEur(BigDecimal unitsPerEur) {
        this.unitsPerEur = unitsPerEur;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(UUID updatedBy) {
        this.updatedBy = updatedBy;
    }
}
