package com.yadony.api.dac7;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * Un fichier DAC7 généré ({@code dac7_export_runs}, V377) : ordre DGFiP unique par année, DEPOSITED seulement après
 * confirmation de l'équipe. Écrit et lu en SQL par {@link Dac7AdminReadModel} ; l'entité ne sert qu'au schéma.
 */
@Entity
@Table(name = "dac7_export_runs", uniqueConstraints = @UniqueConstraint(name = "uq_dac7_export_runs_year_sequence",
        columnNames = {"reporting_year", "sequence"}))
public class Dac7ExportRunEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "reporting_year", nullable = false)
    private int reportingYear;

    @Column(name = "sequence", nullable = false)
    private int sequence;

    @Column(name = "export_mode", nullable = false, length = 10)
    private String exportMode;

    @Column(name = "status", nullable = false, length = 10)
    private String status;

    @Column(name = "file_name", nullable = false, length = 120)
    private String fileName;

    @Column(name = "sellers", nullable = false)
    private int sellers;

    @Column(name = "generated_by")
    private UUID generatedBy;

    @Column(name = "generated_at", nullable = false, insertable = false, updatable = false)
    @org.hibernate.annotations.ColumnDefault("CURRENT_TIMESTAMP")
    private Instant generatedAt;

    @Column(name = "deposited_by")
    private UUID depositedBy;

    @Column(name = "deposited_at")
    private Instant depositedAt;

    protected Dac7ExportRunEntity() {
        // JPA
    }

    public UUID getId() { return id; }
    public int getReportingYear() { return reportingYear; }
    public int getSequence() { return sequence; }
    public String getExportMode() { return exportMode; }
    public String getStatus() { return status; }
    public String getFileName() { return fileName; }
    public int getSellers() { return sellers; }
    public UUID getGeneratedBy() { return generatedBy; }
    public Instant getGeneratedAt() { return generatedAt; }
    public UUID getDepositedBy() { return depositedBy; }
    public Instant getDepositedAt() { return depositedAt; }
}
