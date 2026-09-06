package com.yadony.api.support;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Where;

/**
 * Question/reponse affichee avant d'ouvrir un ticket. Le catalogue est alimente
 * par migration : l'edition depuis le back-office est hors scope initial.
 */
@Entity
@Table(name = "support_predefined_replies")
@Where(clause = "deleted_at IS NULL")
public class SupportPredefinedReplyEntity extends BaseEntity {

    /** Cle stable citee par l'app et les analytics, independante de l'UUID. */
    @Column(name = "code", nullable = false, length = 64, unique = true)
    private String code;

    @Column(name = "category", nullable = false, length = 32)
    private String category;

    @Column(name = "question", nullable = false, length = 300)
    private String question;

    @Column(name = "answer", nullable = false, columnDefinition = "TEXT")
    private String answer;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    public String getCode() { return code; }

    public void setCode(String code) { this.code = code; }

    public String getCategory() { return category; }

    public void setCategory(String category) { this.category = category; }

    public String getQuestion() { return question; }

    public void setQuestion(String question) { this.question = question; }

    public String getAnswer() { return answer; }

    public void setAnswer(String answer) { this.answer = answer; }

    public int getSortOrder() { return sortOrder; }

    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public boolean isActive() { return active; }

    public void setActive(boolean active) { this.active = active; }
}
