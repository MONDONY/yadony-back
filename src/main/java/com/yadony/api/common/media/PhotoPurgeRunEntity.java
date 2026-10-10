package com.yadony.api.common.media;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "photo_purge_runs")
public class PhotoPurgeRunEntity extends BaseEntity {

    public static final String KIND_PURGE = "PURGE";
    public static final String KIND_RECONCILE = "RECONCILE";

    @Column(name = "kind", nullable = false, length = 16) private String kind;
    @Column(name = "dry_run", nullable = false) private boolean dryRun;
    @Column(name = "trigger_source", nullable = false, length = 16) private String triggerSource;
    @Column(name = "started_at", nullable = false) private LocalDateTime startedAt;
    @Column(name = "finished_at") private LocalDateTime finishedAt;
    @Column(name = "eligible", nullable = false) private int eligible;
    @Column(name = "deleted", nullable = false) private int deleted;
    @Column(name = "failed", nullable = false) private int failed;
    @Column(name = "error", columnDefinition = "TEXT") private String error;

    protected PhotoPurgeRunEntity() {
    }

    public PhotoPurgeRunEntity(String kind, boolean dryRun, String triggerSource, LocalDateTime startedAt) {
        this.kind = kind;
        this.dryRun = dryRun;
        this.triggerSource = triggerSource;
        this.startedAt = startedAt;
    }

    public void finish(PhotoPurgeOutcome outcome, LocalDateTime at) {
        this.eligible = outcome.eligible();
        this.deleted = outcome.deleted();
        this.failed = outcome.failed();
        this.finishedAt = at;
    }

    public void fail(String error, LocalDateTime at) {
        this.error = error == null ? "inconnue" : error.length() > 2000 ? error.substring(0, 2000) : error;
        this.finishedAt = at;
    }

    public String getKind() { return kind; }
    public boolean isDryRun() { return dryRun; }
    public String getTriggerSource() { return triggerSource; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public int getEligible() { return eligible; }
    public int getDeleted() { return deleted; }
    public int getFailed() { return failed; }
    public String getError() { return error; }
}
