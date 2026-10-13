package com.yadony.api.auth;

import com.yadony.api.auth.dto.PublishCommitmentsResponse;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Engagements de publication : ce que le voyageur (trajet) ou l'expéditeur (demande
 * d'envoi) accepte avant sa première publication.
 *
 * <p>Le texte vit dans l'app ; le serveur ne connaît que sa version. Changer le texte
 * de façon significative = incrémenter {@link #CURRENT_VERSION} ici ET dans l'app : tous
 * les comptes revoient alors l'écran une fois. Chaque acceptation est prouvée dans
 * {@code audit_log}.
 */
@Service
public class PublishCommitmentService {

    /** Version en vigueur du texte des engagements (voyageur et expéditeur). */
    public static final String CURRENT_VERSION = "2026-10";

    private final UserRepository userRepository;
    private final AuditService auditService;
    private final Clock clock;

    @Autowired
    public PublishCommitmentService(UserRepository userRepository, AuditService auditService) {
        this(userRepository, auditService, Clock.systemUTC());
    }

    PublishCommitmentService(UserRepository userRepository, AuditService auditService, Clock clock) {
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PublishCommitmentsResponse get(UUID userId) {
        UserEntity user = requireUser(userId);
        return new PublishCommitmentsResponse(
                CURRENT_VERSION,
                status(user.getTravelerCommitmentVersion(), user.getTravelerCommitmentAt()),
                status(user.getSenderCommitmentVersion(), user.getSenderCommitmentAt()));
    }

    /**
     * Enregistre l'acceptation. Idempotent : réaccepter la version déjà acceptée ne
     * réécrit rien et n'ajoute pas de ligne d'audit.
     */
    @Transactional
    public void accept(UUID userId, PublishCommitmentRole role, String version) {
        if (!CURRENT_VERSION.equals(version)) {
            // L'app a affiché un texte périmé : son acceptation ne prouve rien du texte
            // en vigueur. L'app recharge et affiche le bon.
            throw new YadonyBusinessException(
                    HttpStatus.CONFLICT,
                    "publish-commitment-outdated",
                    "Publish Commitment Outdated",
                    "Les engagements ont été mis à jour : relisez-les avant de continuer");
        }
        UserEntity user = requireUser(userId);
        Instant now = clock.instant();
        switch (role) {
            case TRAVELER -> {
                if (version.equals(user.getTravelerCommitmentVersion())) return;
                user.setTravelerCommitmentVersion(version);
                user.setTravelerCommitmentAt(now);
            }
            case SENDER -> {
                if (version.equals(user.getSenderCommitmentVersion())) return;
                user.setSenderCommitmentVersion(version);
                user.setSenderCommitmentAt(now);
            }
        }
        userRepository.save(user);
        auditService.log("USER", user.getId(), "PUBLISH_COMMITMENT_ACCEPTED", user.getId(),
                Map.of("role", role.name(), "version", version));
    }

    private static PublishCommitmentsResponse.Status status(String version, Instant at) {
        return new PublishCommitmentsResponse.Status(
                version,
                at == null ? null : at.toString(),
                !CURRENT_VERSION.equals(version));
    }

    private UserEntity requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new YadonyBusinessException(
                        HttpStatus.NOT_FOUND,
                        "user-not-found",
                        "User Not Found",
                        "Utilisateur introuvable"));
    }
}
