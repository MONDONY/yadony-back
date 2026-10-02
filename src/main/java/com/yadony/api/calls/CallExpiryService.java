package com.yadony.api.calls;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Solde les appels que Stream n'a jamais clos (webhook perdu, mal configuré, back redémarré) :
 * sans cela, la conversation resterait bloquée en 409 « appel en cours » pour toujours.
 */
@Service
public class CallExpiryService {

    /** Sonnerie Stream de 30 s, plus une marge pour les webhooks en retard. */
    static final Duration RING_LIMIT = Duration.ofMinutes(2);
    /** Durée maximale d'un appel fixée sur le type audio_call. */
    static final Duration MAX_CALL = Duration.ofSeconds(3600);
    private static final Duration ANSWERED_LIMIT = MAX_CALL.plusMinutes(5);
    private static final Set<CallStatus> LIVE = Set.of(CallStatus.RINGING, CallStatus.ANSWERED);

    private final CallRepository calls;
    private final CallTransitions transitions;
    private final Clock clock;

    @Autowired
    public CallExpiryService(CallRepository calls, CallTransitions transitions) {
        this(calls, transitions, Clock.systemUTC());
    }

    CallExpiryService(CallRepository calls, CallTransitions transitions, Clock clock) {
        this.calls = calls;
        this.transitions = transitions;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    @Transactional
    public void expireStale() {
        expire(calls.findByStatusIn(LIVE));
    }

    @Transactional
    public void expireStale(UUID conversationId) {
        expire(calls.findByConversationIdAndStatusIn(conversationId, LIVE));
    }

    private void expire(List<CallEntity> live) {
        OffsetDateTime now = OffsetDateTime.now(clock.withZone(ZoneOffset.UTC));
        for (CallEntity call : live) {
            if (call.getStatus() == CallStatus.RINGING) {
                if (call.getCreatedAt().atOffset(ZoneOffset.UTC).plus(RING_LIMIT).isBefore(now)) {
                    transitions.finish(call, CallStatus.MISSED, now, null);
                }
            } else {
                OffsetDateTime start = call.getStartedAt() != null
                        ? call.getStartedAt() : call.getCreatedAt().atOffset(ZoneOffset.UTC);
                if (start.plus(ANSWERED_LIMIT).isBefore(now)) {
                    transitions.finish(call, CallStatus.ENDED, start.plus(MAX_CALL), start);
                }
            }
        }
    }
}
