package com.yadony.api.admin.broadcast.segment;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Where;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/** Segment d'audience enregistré : une règle nommée, réutilisable à l'envoi. */
@Entity
@Table(name = "broadcast_segments")
@Where(clause = "deleted_at IS NULL")
public class BroadcastSegmentEntity extends BaseEntity {

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Column(name = "description", length = 300)
    private String description;

    /** JSON canonique de la règle ; {@code JSON} obligatoire pour le jsonb, cf. AdminBroadcastEntity. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule", nullable = false, columnDefinition = "jsonb")
    private String rule;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "updated_by", nullable = false)
    private UUID updatedBy;

    protected BroadcastSegmentEntity() {
        // Hibernate
    }

    public BroadcastSegmentEntity(String name, String description, String rule, UUID adminId) {
        this.name = name;
        this.description = description;
        this.rule = rule;
        this.createdBy = adminId;
        this.updatedBy = adminId;
    }

    public void update(String name, String description, String rule, UUID adminId) {
        this.name = name;
        this.description = description;
        this.rule = rule;
        this.updatedBy = adminId;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getRule() { return rule; }
    public UUID getCreatedBy() { return createdBy; }
    public UUID getUpdatedBy() { return updatedBy; }
}
