package com.yadony.api.calls;

import com.yadony.api.calls.events.CallEndedEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.messaging.ConversationEntity;
import com.yadony.api.messaging.ConversationRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Seul chemin pour faire avancer un appel. Chaque transition est un UPDATE conditionnel : quand deux
 * webhooks (ou un webhook et l'expiration) se croisent, un seul gagne, et lui seul trace et publie.
 */
@Component
public class CallTransitions {

    private final CallRepository calls;
    private final ConversationRepository conversations;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public CallTransitions(CallRepository calls, ConversationRepository conversations, AuditService audit,
                           ApplicationEventPublisher events) {
        this.calls = calls;
        this.conversations = conversations;
        this.audit = audit;
        this.events = events;
    }

    public boolean answer(CallEntity call, OffsetDateTime at) {
        return calls.markAnswered(call.getId(), at) == 1;
    }

    /**
     * @param startedAt début de la conversation s'il est connu par l'événement ; sinon celui déjà
     *                  enregistré au décroché. Ignoré hors ENDED.
     */
    public boolean finish(CallEntity call, CallStatus status, OffsetDateTime at, OffsetDateTime startedAt) {
        OffsetDateTime start = status == CallStatus.ENDED
                ? (startedAt != null ? startedAt : call.getStartedAt()) : null;
        Integer duration = start == null ? null : (int) Math.max(0, Duration.between(start, at).getSeconds());
        if (calls.finishIfLive(call.getId(), status, at, start, duration) != 1) return false;

        Map<String, Object> payload = new HashMap<>();
        payload.put("status", status.name());
        if (duration != null) payload.put("durationSeconds", duration);
        audit.log("CALL", call.getId(), "CALL_ENDED", null, payload);
        String fsId = conversations.findById(call.getConversationId())
                .map(ConversationEntity::getFirestoreConversationId).orElse(null);
        events.publishEvent(new CallEndedEvent(call.getId(), call.getConversationId(), fsId,
                call.getCallerId(), call.getCalleeId(), status, duration));
        return true;
    }
}
