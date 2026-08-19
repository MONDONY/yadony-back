package com.yadony.api.config;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

import java.util.UUID;

/**
 * Une ligne = un parametre. Ces lignes ne sont JAMAIS supprimees : la contrainte d'unicite
 * porte sur setting_key sans tenir compte de deleted_at, une ligne effacee en douceur
 * bloquerait donc toute reinsertion de la meme cle.
 */
@Entity
@Table(name = "platform_settings")
@Where(clause = "deleted_at IS NULL")
public class PlatformSettingEntity extends BaseEntity {

    @Column(name = "setting_key", nullable = false, length = 60, unique = true)
    private String settingKey;

    @Column(name = "setting_value", nullable = false, length = 255)
    private String settingValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false, length = 10)
    private PlatformSettingType valueType;

    @Column(name = "updated_by")
    private UUID updatedBy;

    protected PlatformSettingEntity() {
        // Hibernate
    }

    public PlatformSettingEntity(String settingKey, String settingValue, PlatformSettingType valueType) {
        this.settingKey = settingKey;
        this.settingValue = settingValue;
        this.valueType = valueType;
    }

    public String getSettingKey() { return settingKey; }
    public String getSettingValue() { return settingValue; }
    public void setSettingValue(String settingValue) { this.settingValue = settingValue; }
    public PlatformSettingType getValueType() { return valueType; }
    public UUID getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(UUID updatedBy) { this.updatedBy = updatedBy; }
}
