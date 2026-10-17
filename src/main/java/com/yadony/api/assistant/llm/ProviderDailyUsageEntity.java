package com.yadony.api.assistant.llm;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Compteurs du jour (UTC) d'un fournisseur d'IA (V327). Table technique : pas de {@code BaseEntity}
 * ni de soft delete. Les écritures passent uniquement par les requêtes atomiques de
 * {@link ProviderDailyUsageRepository}, jamais par {@code save()}.
 */
@Entity
@Table(name = "assistant_provider_daily_usage")
public class ProviderDailyUsageEntity {

    @EmbeddedId
    private Key id;

    @Column(nullable = false)
    private int requests;

    @Column(nullable = false)
    private int successes;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected ProviderDailyUsageEntity() {
    }

    public Key getId() { return id; }
    public int getRequests() { return requests; }
    public int getSuccesses() { return successes; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    @Embeddable
    public record Key(@Column(name = "provider_id", nullable = false) UUID providerId,
                      @Column(name = "usage_date", nullable = false) LocalDate usageDate) implements Serializable {
    }
}
