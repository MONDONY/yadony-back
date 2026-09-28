package com.yadony.api.kyc;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Identifiants de session refuses ou revoques par un administrateur (V270).
 *
 * <p>La ligne {@code kyc_verifications} perd sa decision a la nouvelle session ; ce registre, lui,
 * la garde. Didit resservant une session inachevee du meme utilisateur, une session refusee peut
 * revenir : tout webhook positif (verification, passage en revue) qui la porte est ignore, et
 * {@code createSession} en exige une neuve.
 */
@Component
public class KycRefusedSessionRegistry {

    private final KycRefusedSessionRepository repository;

    public KycRefusedSessionRegistry(KycRefusedSessionRepository repository) {
        this.repository = repository;
    }

    /** Idempotent ; sans effet pour une ligne sans session. */
    public void remember(KycVerificationEntity kyc, KycDecisionKind kind, UUID adminId) {
        String sessionId = kyc.getVerificationSessionId();
        if (sessionId == null || sessionId.isBlank() || repository.existsBySessionId(sessionId)) {
            return;
        }
        repository.save(new KycRefusedSessionEntity(kyc.getUserId(), kyc.getProvider(), sessionId, kind, adminId,
                LocalDateTime.now(ZoneOffset.UTC)));
    }

    public boolean isRefused(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        return repository.existsBySessionId(sessionId);
    }
}
