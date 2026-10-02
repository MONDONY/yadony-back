package com.yadony.api.calls;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;

/** Applique les événements Stream aux appels, via CallTransitions (idempotent et sûr en concurrence). */
@Service
public class CallWebhookService {

    private static final Logger log = LoggerFactory.getLogger(CallWebhookService.class);
    private static final String CID_PREFIX = "audio_call:";
    /** Raisons Stream d'un call.rejected qui ne sont pas un refus de l'appelé. */
    private static final Set<String> NOT_A_DECLINE = Set.of("cancel", "timeout");

    private final CallRepository calls;
    private final CallTransitions transitions;
    private final Clock clock;

    @Autowired
    public CallWebhookService(CallRepository calls, CallTransitions transitions) {
        this(calls, transitions, Clock.systemUTC());
    }

    CallWebhookService(CallRepository calls, CallTransitions transitions, Clock clock) {
        this.calls = calls;
        this.transitions = transitions;
        this.clock = clock;
    }

    @Transactional
    public void handle(JsonNode event) {
        String cid = event.path("call_cid").asText("");
        if (!cid.startsWith(CID_PREFIX)) return;
        CallEntity call = calls.findByStreamCallId(cid.substring(CID_PREFIX.length())).orElse(null);
        if (call == null) {
            log.info("Webhook Stream {} pour un appel inconnu {}", event.path("type").asText(""), cid);
            return;
        }
        OffsetDateTime at = parse(event.path("created_at").asText(null));
        String callee = call.getCalleeId().toString();

        switch (event.path("type").asText("")) {
            case "call.session_participant_joined" -> {
                if (callee.equals(event.path("participant").path("user").path("id").asText(""))) {
                    transitions.answer(call, at);
                }
            }
            case "call.missed" -> transitions.finish(call, CallStatus.MISSED, at, null);
            case "call.rejected" -> {
                // L'appelant qui annule, ou la sonnerie qui expire, émet aussi call.rejected.
                boolean declinedByCallee = callee.equals(event.path("user").path("id").asText(""))
                        && !NOT_A_DECLINE.contains(event.path("reason").asText(""));
                transitions.finish(call, declinedByCallee ? CallStatus.REJECTED : CallStatus.MISSED, at, null);
            }
            case "call.session_ended", "call.ended" -> {
                JsonNode session = event.path("call").path("session");
                // accepted_by rend la décision indépendante de l'ordre d'arrivée des webhooks.
                OffsetDateTime acceptedAt = parseOrNull(session.path("accepted_by").path(callee).asText(null));
                OffsetDateTime endedAt = parseOrNull(session.path("ended_at").asText(null));
                boolean answered = acceptedAt != null || call.getStatus() == CallStatus.ANSWERED;
                transitions.finish(call, answered ? CallStatus.ENDED : CallStatus.MISSED,
                        endedAt != null ? endedAt : at, acceptedAt);
            }
            default -> { }
        }
    }

    private OffsetDateTime parse(String raw) {
        OffsetDateTime parsed = parseOrNull(raw);
        return parsed != null ? parsed : OffsetDateTime.now(clock.withZone(ZoneOffset.UTC));
    }

    private static OffsetDateTime parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return OffsetDateTime.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
