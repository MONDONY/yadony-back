package com.yadony.api.assistant.provider;

import com.yadony.api.common.BaseEntity;
import com.yadony.api.common.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

@Entity
@Table(name = "assistant_llm_providers")
@Where(clause = "deleted_at IS NULL")
public class AssistantLlmProviderEntity extends BaseEntity {

    @Column(nullable = false, length = 60)
    private String name;

    @Column(nullable = false, length = 40, updatable = false)
    private String slug;

    @Column(name = "base_url", nullable = false, length = 255)
    private String baseUrl;

    @Column(nullable = false, length = 120)
    private String model;

    /** Clé d'API en clair côté Java, chiffrée en base. Jamais dans un WHERE, un tri ni un UNIQUE. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "api_key_encrypted", nullable = false, columnDefinition = "TEXT")
    private String apiKey;

    @Column(name = "api_key_last4", nullable = false, length = 4)
    private String apiKeyLast4;

    @Column(name = "supports_json_mode", nullable = false)
    private boolean supportsJsonMode = true;

    @Column(name = "daily_request_limit")
    private Integer dailyRequestLimit;

    @Column(nullable = false)
    private int priority;

    @Column(nullable = false)
    private boolean enabled = true;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getApiKey() { return apiKey; }
    public String getApiKeyLast4() { return apiKeyLast4; }
    public boolean isSupportsJsonMode() { return supportsJsonMode; }
    public void setSupportsJsonMode(boolean supportsJsonMode) { this.supportsJsonMode = supportsJsonMode; }
    public Integer getDailyRequestLimit() { return dailyRequestLimit; }
    public void setDailyRequestLimit(Integer dailyRequestLimit) { this.dailyRequestLimit = dailyRequestLimit; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /** Pose la clé et ses 4 derniers caractères ensemble : ils ne divergent jamais. */
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
        this.apiKeyLast4 = apiKey.substring(Math.max(0, apiKey.length() - 4));
    }
}
