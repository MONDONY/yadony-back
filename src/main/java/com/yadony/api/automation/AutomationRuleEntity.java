package com.yadony.api.automation;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "automation_rules")
@SQLRestriction("deleted_at IS NULL")
public class AutomationRuleEntity extends BaseEntity {

    @Column(name = "traveler_id", nullable = false)
    private UUID travelerId;

    @Column(name = "rule_type", nullable = false, length = 20)
    private String ruleType;

    @Column(name = "preset_rule_id", length = 50)
    private String presetRuleId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /**
     * Vrai si cette règle a été éteinte par la perte du statut PRO, et non par
     * le voyageur. Seules ces règles sont rallumées au réabonnement.
     */
    @Column(name = "disabled_by_downgrade", nullable = false)
    private boolean disabledByDowngrade = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "conditions", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> conditions = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "action", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> action = Map.of();

    public AutomationRuleEntity() {}

    public UUID getTravelerId() { return travelerId; }
    public void setTravelerId(UUID travelerId) { this.travelerId = travelerId; }

    public String getRuleType() { return ruleType; }
    public void setRuleType(String ruleType) { this.ruleType = ruleType; }

    public String getPresetRuleId() { return presetRuleId; }
    public void setPresetRuleId(String presetRuleId) { this.presetRuleId = presetRuleId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isDisabledByDowngrade() { return disabledByDowngrade; }
    public void setDisabledByDowngrade(boolean disabledByDowngrade) { this.disabledByDowngrade = disabledByDowngrade; }

    public List<Map<String, Object>> getConditions() { return conditions; }
    public void setConditions(List<Map<String, Object>> conditions) { this.conditions = conditions; }

    public Map<String, Object> getAction() { return action; }
    public void setAction(Map<String, Object> action) { this.action = action; }
}
