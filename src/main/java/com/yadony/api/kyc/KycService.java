package com.yadony.api.kyc;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyNotFoundException;
import com.yadony.api.kyc.dto.KycSessionResponse;
import com.yadony.api.kyc.dto.KycStatusResponse;
import com.stripe.model.identity.VerificationSession;
import com.stripe.param.identity.VerificationSessionCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;

@Service
public class KycService {

    private static final Logger log = LoggerFactory.getLogger(KycService.class);

    private final KycRepository kycRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    @Value("${yadony.kyc.return-url:https://yadony.com/kyc/complete}")
    private String kycReturnUrl;

    public KycService(KycRepository kycRepository,
                      UserRepository userRepository,
                      AuditService auditService) {
        this.kycRepository = kycRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Transactional
    public KycSessionResponse createSession(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyNotFoundException("Utilisateur introuvable"));

        if (user.getKycStatus() == KycStatus.VERIFIED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "KYC déjà vérifié");
        }

        // Idempotency: return existing session if already PENDING to avoid duplicate Stripe sessions
        if (user.getKycStatus() == KycStatus.PENDING) {
            Optional<KycVerificationEntity> existing = kycRepository.findByUserId(user.getId());
            if (existing.isPresent() && existing.get().getStripeVerificationSessionId() != null) {
                String existingSessionId = existing.get().getStripeVerificationSessionId();
                try {
                    VerificationSession existingSession = VerificationSession.retrieve(existingSessionId);
                    return new KycSessionResponse(existingSession.getUrl(), existingSessionId, "PENDING");
                } catch (Exception e) {
                    log.warn("Could not retrieve existing KYC session {}, creating new one", existingSessionId);
                }
            }
        }

        // Transition NOT_STARTED → PENDING when session is created
        if (user.getKycStatus() == KycStatus.NOT_STARTED) {
            user.setKycStatus(KycStatus.PENDING);
            userRepository.save(user);
        }

        try {
            VerificationSessionCreateParams params = VerificationSessionCreateParams.builder()
                    .setType(VerificationSessionCreateParams.Type.DOCUMENT)
                    .setReturnUrl(kycReturnUrl)
                    .putMetadata("user_id", user.getId().toString())
                    .setOptions(
                            VerificationSessionCreateParams.Options.builder()
                                    .setDocument(
                                            VerificationSessionCreateParams.Options.Document.builder()
                                                    .setRequireLiveCapture(true)
                                                    .setRequireMatchingSelfie(true)
                                                    .addAllowedType(VerificationSessionCreateParams.Options.Document.AllowedType.ID_CARD)
                                                    .addAllowedType(VerificationSessionCreateParams.Options.Document.AllowedType.PASSPORT)
                                                    .addAllowedType(VerificationSessionCreateParams.Options.Document.AllowedType.DRIVING_LICENSE)
                                                    .build()
                                    )
                                    .build()
                    )
                    .build();

            VerificationSession session = VerificationSession.create(params);

            // Find existing or create new KYC record
            KycVerificationEntity kyc = kycRepository.findByUserId(user.getId())
                    .orElseGet(() -> {
                        KycVerificationEntity newKyc = new KycVerificationEntity();
                        newKyc.setUserId(user.getId());
                        return newKyc;
                    });

            kyc.setStripeVerificationSessionId(session.getId());
            kyc.setStatus(KycVerificationStatus.PENDING);
            kyc.setRejectionReason(null);
            kycRepository.save(kyc);

            auditService.log("kyc_verification", kyc.getId(), "KYC_SESSION_CREATED",
                    user.getId(), Map.of("sessionId", session.getId()));

            return new KycSessionResponse(session.getUrl(), session.getId(), "PENDING");

        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to create Stripe Identity session for user {}", user.getId(), e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Impossible de créer la session de vérification");
        }
    }

    @Transactional
    public void abandonSession(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyNotFoundException("Utilisateur introuvable"));

        if (user.getKycStatus() != KycStatus.PENDING) return;

        user.setKycStatus(KycStatus.NOT_STARTED);
        userRepository.save(user);

        // Best-effort: cancel the Stripe session so it doesn't linger PENDING forever.
        // Never blocks the local abandon if Stripe is unreachable or the session already terminated.
        kycRepository.findByUserId(user.getId())
                .map(KycVerificationEntity::getStripeVerificationSessionId)
                .ifPresent(sessionId -> {
                    try {
                        VerificationSession.retrieve(sessionId).cancel();
                    } catch (Exception e) {
                        log.warn("Could not cancel Stripe KYC session {} on abandon: {}", sessionId, e.getMessage());
                    }
                });

        auditService.log("kyc_verification", user.getId(), "KYC_SESSION_ABANDONED",
                user.getId(), Map.of("reason", "user_closed_webview"));
    }

    @Transactional(readOnly = true)
    public KycStatusResponse getStatus(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyNotFoundException("Utilisateur introuvable"));

        Optional<KycVerificationEntity> kyc = kycRepository.findByUserId(user.getId());

        String verificationStatus = kyc
                .map(k -> k.getStatus().name())
                .orElse("NOT_STARTED");

        return new KycStatusResponse(
                user.getKycStatus().name(),
                verificationStatus,
                kyc.map(KycVerificationEntity::getRejectionReason).orElse(null),
                kyc.map(KycVerificationEntity::getRejectionCode).orElse(null)
        );
    }

}
