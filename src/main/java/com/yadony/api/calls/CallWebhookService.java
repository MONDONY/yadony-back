package com.yadony.api.calls;

import com.fasterxml.jackson.databind.JsonNode;
import com.yadony.api.calls.events.CallEndedEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.messaging.ConversationEntity;
import com.yadony.api.messaging.ConversationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

/** Applique les événements Stream à la table calls. Idempotent : un appel terminé ne bouge plus. */
@Service
public class CallWebhookService {

    private static final String CID_PREFIX = "audio_call:";

    private final CallRepository calls;
    private final ConversationRepository conversations;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Autowired
    public CallWebhookService(CallRepository calls, ConversationRepository conversations, AuditService audit,
                              ApplicationEventPublisher events) {
        this(calls, conversations, audit, events, Clock.systemUTC());
    }

    CallWebhookService(CallRepository calls, ConversationRepository conversations, AuditService audit,
                       ApplicationEventPublisher events, Clock clock) {
        this.calls = calls;
        this.conversations = conversations;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public void handle(JsonNode event) {
        String cid = event.path("call_cid").asText("");
        if (!cid.startsWith(CID_PREFIX)) return;
        CallEntity call = calls.findByStreamCallId(cid.substring(CID_PREFIX.length())).orElse(null);
        if (call == null) return;
        OffsetDateTime at = timestamp(event);

        switch (event.path("type").asText("")) {
            case "call.session_participant_joined" -> {
                String userId = event.path("participant").path("user").path("id").asText("");
                if (call.getCalleeId().toString().equals(userId)) call.answer(at);
            }
            case "call.missed" -> finish(call, CallStatus.MISSED, at);
            case "call.rejected" -> finish(call, CallStatus.REJECTED, at);
            // Fin de session sans que l'appelé ait décroché (l'appelant a raccroché) : appel manqué.
            case "call.session_ended", "call.ended" -> finish(call,
                    call.getStatus() == CallStatus.ANSWERED ? CallStatus.ENDED : CallStatus.MISSED, at);
            default -> { }
        }
        calls.save(call);
    }

    private void finish(CallEntity call, CallStatus status, OffsetDateTime at) {
        if (!call.finish(status, at)) return;
        Map<String, Object> payload = new HashMap<>();
        payload.put("status", status.name());
        if (call.getDurationSeconds() != null) payload.put("durationSeconds", call.getDurationSeconds());
        audit.log("CALL", call.getId(), "CALL_ENDED", null, payload);
        String fsId = conversations.findById(call.getConversationId())
                .map(ConversationEntity::getFirestoreConversationId).orElse(null);
        events.publishEvent(new CallEndedEvent(call.getId(), call.getConversationId(), fsId,
                call.getCallerId(), call.getCalleeId(), status, call.getDurationSeconds()));
    }

    private OffsetDateTime timestamp(JsonNode event) {
        String raw = event.path("created_at").asText(null);
        try {
            return raw == null ? OffsetDateTime.now(clock.withZone(ZoneOffset.UTC)) : OffsetDateTime.parse(raw);
        } catch (Exception e) {
            return OffsetDateTime.now(clock.withZone(ZoneOffset.UTC));
        }
    }
}
