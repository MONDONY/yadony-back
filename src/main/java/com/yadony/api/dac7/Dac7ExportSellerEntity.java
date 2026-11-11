package com.yadony.api.dac7;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/**
 * Un voyageur d'un fichier DAC7 généré ({@code dac7_export_sellers}, V377) : retenu avec sa référence, ou écarté avec
 * sa raison. Écrit et lu en SQL par {@link Dac7AdminReadModel} ; l'entité ne sert qu'au schéma. Sans donnée
 * personnelle.
 */
@Entity
@Immutable
@Table(name = "dac7_export_sellers",
        indexes = {@Index(name = "idx_dac7_export_sellers_year_user", columnList = "reporting_year, user_id"),
                @Index(name = "idx_dac7_export_sellers_run", columnList = "export_run_id")})
public class Dac7ExportSellerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reporting_year", nullable = false, updatable = false)
    private int reportingYear;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "export_run_id", nullable = false, updatable = false)
    private UUID exportRunId;

    @Column(name = "status", nullable = false, length = 10, updatable = false)
    private String status;

    @Column(name = "doc_ref_id", length = 200, updatable = false)
    private String docRefId;

    @Column(name = "reason", length = 255, updatable = false)
    private String reason;

    @Column(name = "sequence", nullable = false, updatable = false)
    private int sequence;

    @Column(name = "export_mode", nullable = false, length = 10, updatable = false)
    private String exportMode;

    @Column(name = "exported_by", updatable = false)
    private UUID exportedBy;

    @Column(name = "exported_at", nullable = false, updatable = false, insertable = false)
    @org.hibernate.annotations.ColumnDefault("CURRENT_TIMESTAMP")
    private Instant exportedAt;

    protected Dac7ExportSellerEntity() {
        // JPA
    }

    public Long getId() { return id; }
    public int getReportingYear() { return reportingYear; }
    public UUID getUserId() { return userId; }
    public UUID getExportRunId() { return exportRunId; }
    public String getStatus() { return status; }
    public String getDocRefId() { return docRefId; }
    public String getReason() { return reason; }
    public int getSequence() { return sequence; }
    public String getExportMode() { return exportMode; }
    public UUID getExportedBy() { return exportedBy; }
    public Instant getExportedAt() { return exportedAt; }
}
