package com.yadony.api.calls;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.calls.dto.CallTokenResponse;
import com.yadony.api.calls.dto.StartCallResponse;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.YadonyNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Jeton Stream de l'utilisateur et lancement d'un appel, seulement si la règle d'éligibilité l'autorise. */
@Service
public class CallService {

    private static final Set<CallStatus> LIVE = Set.of(CallStatus.RINGING, CallStatus.ANSWERED);

    private final CallEligibilityService eligibility;
    private final StreamClient stream;
    private final StreamTokenService tokens;
    private final CallRepository calls;
    private final UserRepository users;
    private final AuditService audit;
    private final StreamProperties properties;

    public CallService(CallEligibilityService eligibility, StreamClient stream, StreamTokenService tokens,
                       CallRepository calls, UserRepository users, AuditService audit, StreamProperties properties) {
        this.eligibility = eligibility;
        this.stream = stream;
        this.tokens = tokens;
        this.calls = calls;
        this.users = users;
        this.audit = audit;
        this.properties = properties;
    }

    public CallTokenResponse token(String firebaseUid) {
        if (!properties.configured()) throw disabled();
        UserEntity me = currentUser(firebaseUid);
        var issued = tokens.userToken(me.getId());
        return new CallTokenResponse(properties.apiKey(), me.getId().toString(), issued.token(), issued.expiresAt());
    }

    @Transactional
    public StartCallResponse start(String firebaseUid, UUID conversationId) {
        UserEntity caller = currentUser(firebaseUid);
        var e = eligibility.check(caller.getId(), conversationId);
        if (!e.allowed()) throw refusal(e.reason(), conversationId);
        if (calls.existsByConversationIdAndStatusIn(conversationId, LIVE)) {
            throw new YadonyBusinessException(HttpStatus.CONFLICT, "call-already-in-progress",
                    "Call Already In Progress", "Un appel est déjà en cours dans cette conversation.");
        }

        UserEntity callee = users.findById(e.calleeId())
                .orElseThrow(() -> refusal(CallEligibilityService.Reason.CALLEE_UNAVAILABLE, conversationId));
        String callId = UUID.randomUUID().toString();
        try {
            stream.upsertUsers(List.of(streamUser(caller), streamUser(callee)));
            stream.createRingingCall(callId, caller.getId(), List.of(caller.getId(), callee.getId()));
        } catch (StreamClient.StreamUnavailableException ex) {
            throw new YadonyBusinessException(HttpStatus.SERVICE_UNAVAILABLE, "call-provider-unavailable",
                    "Call Provider Unavailable", "L'appel est momentanément indisponible. Réessayez dans un instant.");
        }

        CallEntity call = calls.save(new CallEntity(conversationId, e.conversation().getBidId(),
                caller.getId(), callee.getId(), callId));
        audit.log("CALL", call.getId(), "CALL_STARTED", caller.getId(),
                Map.of("conversationId", conversationId.toString(), "calleeId", callee.getId().toString()));
        return new StartCallResponse(callId, properties.callType());
    }

    /** Nom affiché sur l'écran d'appel de l'autre : prénom + initiale du nom, jamais le nom complet. */
    private static StreamClient.StreamUser streamUser(UserEntity u) {
        String last = u.getLastName();
        String name = (u.getFirstName() == null ? "" : u.getFirstName())
                + (last == null || last.isBlank() ? "" : " " + last.charAt(0) + ".");
        return new StreamClient.StreamUser(u.getId(), name.isBlank() ? "Yadony" : name.trim(), u.getAvatarUrl());
    }

    private RuntimeException refusal(CallEligibilityService.Reason reason, UUID conversationId) {
        return switch (reason) {
            case CALLS_DISABLED -> disabled();
            case NOT_FOUND -> new YadonyNotFoundException("Conversation", conversationId);
            case NOT_PARTICIPANT -> new YadonyBusinessException(HttpStatus.FORBIDDEN, "call-not-participant",
                    "Call Not Participant", "Vous ne participez pas à cette conversation.");
            case CONVERSATION_CLOSED -> new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "call-conversation-closed", "Call Conversation Closed", "Cette conversation est fermée.");
            case OUT_OF_WINDOW -> new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "call-out-of-window", "Call Out Of Window",
                    "L'appel est possible de l'acceptation de la commande jusqu'à 3 jours après la livraison.");
            case BLOCKED -> new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "call-blocked",
                    "Call Blocked", "Vous ne pouvez pas appeler cette personne.");
            case CALLEE_UNAVAILABLE -> new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "call-callee-unavailable", "Call Callee Unavailable", "Cette personne n'est pas joignable.");
        };
    }

    private static YadonyBusinessException disabled() {
        return new YadonyBusinessException(HttpStatus.SERVICE_UNAVAILABLE, "calls-disabled",
                "Calls Disabled", "Les appels ne sont pas disponibles pour le moment.");
    }

    private UserEntity currentUser(String firebaseUid) {
        return users.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyNotFoundException("Utilisateur introuvable"));
    }
}
