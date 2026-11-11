package com.yadony.api.dac7;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/**
 * Récapitulatif DAC7 annuel envoyé à un voyageur ({@code dac7_seller_statements}, V377) : une ligne par voyageur et
 * par année, posée avant l'envoi par {@link Dac7SellerStatementService}. Lue et écrite en SQL ; l'entité ne sert qu'au
 * schéma. Aucun montant ni aucune donnée d'identité.
 */
@Entity
@Immutable
@Table(name = "dac7_seller_statements",
        uniqueConstraints = @UniqueConstraint(name = "uq_dac7_seller_statements_user_year",
                columnNames = {"user_id", "reporting_year"}))
public class Dac7SellerStatementEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "reporting_year", nullable = false, updatable = false)
    private int reportingYear;

    @Column(name = "sent_by", updatable = false)
    private UUID sentBy;

    @Column(name = "sent_at", nullable = false, updatable = false, insertable = false)
    @org.hibernate.annotations.ColumnDefault("CURRENT_TIMESTAMP")
    private Instant sentAt;

    protected Dac7SellerStatementEntity() {
        // JPA
    }

    public Long getId() { return id; }
    public UUID getUserId() { return userId; }
    public int getReportingYear() { return reportingYear; }
    public UUID getSentBy() { return sentBy; }
    public Instant getSentAt() { return sentAt; }
}
