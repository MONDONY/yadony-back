package com.yadony.api.calls;

import com.yadony.api.common.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.Where;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "calls")
@Where(clause = "deleted_at IS NULL")
public class CallEntity extends BaseEntity {

    @Column(name = "conversation_id", nullable = false) private UUID conversationId;
    @Column(name = "bid_id", nullable = false) private UUID bidId;
    @Column(name = "caller_id", nullable = false) private UUID callerId;
    @Column(name = "callee_id", nullable = false) private UUID calleeId;
    @Column(name = "stream_call_id", nullable = false, unique = true, length = 100) private String streamCallId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CallStatus status = CallStatus.RINGING;

    @Column(name = "started_at") private OffsetDateTime startedAt;
    @Column(name = "ended_at") private OffsetDateTime endedAt;
    @Column(name = "duration_s") private Integer durationSeconds;

    protected CallEntity() {}

    public CallEntity(UUID conversationId, UUID bidId, UUID callerId, UUID calleeId, String streamCallId) {
        this.conversationId = conversationId;
        this.bidId = bidId;
        this.callerId = callerId;
        this.calleeId = calleeId;
        this.streamCallId = streamCallId;
    }

    /** L'appelé a rejoint l'appel. Sans effet hors sonnerie. */
    public void answer(OffsetDateTime at) {
        if (status != CallStatus.RINGING) return;
        status = CallStatus.ANSWERED;
        startedAt = at;
    }

    /** Passe à un statut terminal. Renvoie false si l'appel l'était déjà (webhook rejoué). */
    public boolean finish(CallStatus terminal, OffsetDateTime at) {
        if (status.isTerminal()) return false;
        status = terminal;
        endedAt = at;
        if (startedAt != null) {
            durationSeconds = (int) Math.max(0, Duration.between(startedAt, at).getSeconds());
        }
        return true;
    }

    public UUID getConversationId() { return conversationId; }
    public UUID getBidId() { return bidId; }
    public UUID getCallerId() { return callerId; }
    public UUID getCalleeId() { return calleeId; }
    public String getStreamCallId() { return streamCallId; }
    public CallStatus getStatus() { return status; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public OffsetDateTime getEndedAt() { return endedAt; }
    public Integer getDurationSeconds() { return durationSeconds; }
}
