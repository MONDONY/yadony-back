package com.yadony.api.support;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

/** Réponse type que les agents insèrent dans leurs réponses (distincte de la FAQ {@link SupportPredefinedReplyEntity}). */
@Entity
@Table(name = "support_reply_templates")
@Where(clause = "deleted_at IS NULL")
public class SupportReplyTemplateEntity extends BaseEntity {

    @Column(name = "label", nullable = false, length = 60)
    private String label;

    @Column(name = "content", nullable = false, length = 4000)
    private String content;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public String getLabel() { return label; }

    public void setLabel(String label) { this.label = label; }

    public String getContent() { return content; }

    public void setContent(String content) { this.content = content; }

    public int getSortOrder() { return sortOrder; }

    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
