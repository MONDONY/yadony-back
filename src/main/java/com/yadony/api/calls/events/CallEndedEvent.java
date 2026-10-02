package com.yadony.api.calls.events;

import com.yadony.api.calls.CallStatus;

import java.util.UUID;

/** Un appel vient de passer à un statut terminal (ENDED, MISSED, REJECTED). Publié une seule fois par appel. */
public record CallEndedEvent(UUID callId, UUID conversationId, String firestoreConversationId,
                             UUID callerId, UUID calleeId, CallStatus status, Integer durationSeconds) {}
