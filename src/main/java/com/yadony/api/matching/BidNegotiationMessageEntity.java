package com.yadony.api.matching;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Append-only entity — never UPDATE or DELETE rows in bid_negotiation_messages.
 * Use {@link #create(UUID, UUID, BidNegotiationMessageKind, BigDecimal, String)}
 * from application code instead of the no-arg constructor.
 */
@Entity
@Table(name = "bid_negotiation_messages")
public class BidNegotiationMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "bid_id", nullable = false)
    private UUID bidId;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BidNegotiationMessageKind kind;

    @Column(name = "proposed_gross_eur", precision = 10, scale = 2)
    private BigDecimal proposedGrossEur;

    @Column(length = 280)
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { this.createdAt = LocalDateTime.now(ZoneOffset.UTC); }

    public BidNegotiationMessageEntity() { /* JPA */ }

    public static BidNegotiationMessageEntity create(UUID bidId, UUID authorId,
                                                     BidNegotiationMessageKind kind,
                                                     BigDecimal proposedGrossEur,
                                                     String body) {
        BidNegotiationMessageEntity e = new BidNegotiationMessageEntity();
        e.bidId = bidId;
        e.authorId = authorId;
        e.kind = kind;
        e.proposedGrossEur = proposedGrossEur;
        e.body = body;
        return e;
    }

    public UUID getId() { return id; }
    public UUID getBidId() { return bidId; }
    public UUID getAuthorId() { return authorId; }
    public BidNegotiationMessageKind getKind() { return kind; }
    public BigDecimal getProposedGrossEur() { return proposedGrossEur; }
    public String getBody() { return body; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
