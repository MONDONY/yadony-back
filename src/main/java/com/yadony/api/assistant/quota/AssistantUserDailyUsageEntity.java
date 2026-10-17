package com.yadony.api.assistant.quota;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Questions posées à l'assistant par un utilisateur un jour donné (UTC), V329. Table technique : pas de
 * {@code BaseEntity} ni de soft delete. Les écritures passent uniquement par les requêtes atomiques de
 * {@link AssistantUserDailyUsageRepository}, jamais par {@code save()}.
 */
@Entity
@Table(name = "assistant_user_daily_usage")
public class AssistantUserDailyUsageEntity {

    @EmbeddedId
    private Key id;

    @Column(nullable = false)
    private int questions;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected AssistantUserDailyUsageEntity() {
    }

    public Key getId() { return id; }
    public int getQuestions() { return questions; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    @Embeddable
    public record Key(@Column(name = "firebase_uid", nullable = false, length = 128) String firebaseUid,
                      @Column(name = "usage_date", nullable = false) LocalDate usageDate) implements Serializable {
    }
}
