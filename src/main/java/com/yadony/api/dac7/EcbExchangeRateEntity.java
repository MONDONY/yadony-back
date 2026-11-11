package com.yadony.api.dac7;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Un taux de référence BCE d'un jour ({@code ecb_exchange_rates}, V377). Lu et écrit en SQL par
 * {@link EcbExchangeRateProvider} ; l'entité ne sert qu'au schéma (validation sur PostgreSQL, génération sur H2).
 * N'étend pas {@code BaseEntity} : un taux publié ne se modifie ni ne se supprime.
 */
@Entity
@Immutable
@Table(name = "ecb_exchange_rates",
        uniqueConstraints = @UniqueConstraint(name = "uq_ecb_exchange_rates", columnNames = {"currency", "rate_date"}))
public class EcbExchangeRateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "rate_date", nullable = false, updatable = false)
    private LocalDate rateDate;

    @Column(name = "units_per_eur", nullable = false, precision = 18, scale = 6, updatable = false)
    private BigDecimal unitsPerEur;

    @Column(name = "fetched_at", nullable = false, updatable = false, insertable = false)
    @org.hibernate.annotations.ColumnDefault("CURRENT_TIMESTAMP")
    private Instant fetchedAt;

    protected EcbExchangeRateEntity() {
        // JPA
    }

    public Long getId() { return id; }
    public String getCurrency() { return currency; }
    public LocalDate getRateDate() { return rateDate; }
    public BigDecimal getUnitsPerEur() { return unitsPerEur; }
    public Instant getFetchedAt() { return fetchedAt; }
}
