package com.yadony.api.kyc;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyNotFoundException;
import com.yadony.api.kyc.dto.KycSessionResponse;
import com.yadony.api.kyc.dto.KycStatusResponse;
import com.yadony.api.kyc.events.UserKycVerifiedEvent;
import com.stripe.model.identity.VerificationSession;
import com.stripe.param.identity.VerificationSessionCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class KycService {

    private static final Logger log = LoggerFactory.getLogger(KycService.class);

    /**
     * Grâce minimale avant reconciliation forcée — laisse le temps au webhook Stripe
     * d'arriver normalement avant que le scheduler ne retéléphone Stripe pour le même compte.
     */
    static final int RECONCILE_MIN_AGE_MINUTES = 10;

    private final KycRepository kycRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${yadony.kyc.return-url:https://yadony.com/kyc/complete}")
    private String kycReturnUrl;

    public KycService(KycRepository kycRepository,
                      UserRepository userRepository,
                      AuditService auditService,
                      ApplicationEventPublisher eventPublisher) {
        this.kycRepository = kycRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
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
        String rejectionReason = kyc.map(KycVerificationEntity::getRejectionReason).orElse(null);

        return new KycStatusResponse(user.getKycStatus().name(), verificationStatus, rejectionReason);
    }

    /**
     * Filet de sécurité contre les webhooks Stripe perdus (mauvais secret, endpoint mal
     * enregistré, panne réseau) : sans ce scheduler, un utilisateur dont le webhook n'arrive
     * jamais reste bloqué en PENDING indéfiniment, sans qu'aucune action ne le débloque.
     * Retéléphone Stripe pour chaque session encore PENDING passé {@link #RECONCILE_MIN_AGE_MINUTES},
     * et rejoue la même transition d'état que {@link KycStripeWebhookHandler}.
     */
    @Scheduled(fixedDelay = 10 * 60 * 1000)
    public void reconcilePendingVerifications() {
        // pgjdbc reinterprets a bound LocalDateTime using the JVM's default zone when
        // comparing against a `timestamp with time zone` column — NOT as literal UTC,
        // even though the value was built with ZoneOffset.UTC. On a JVM whose default zone
        // isn't UTC (e.g. Europe/Paris in dev), `LocalDateTime.now(ZoneOffset.UTC)` here
        // silently shifts the effective threshold by the zone offset (verified empirically:
        // a row 20min stale wasn't picked up, one 3h stale was). Building the threshold from
        // an Instant and rendering it through the JVM's own default zone makes the value
        // round-trip correctly through that same reinterpretation, regardless of which zone
        // the JVM runs in.
        LocalDateTime threshold = LocalDateTime.ofInstant(
                Instant.now().minus(RECONCILE_MIN_AGE_MINUTES, ChronoUnit.MINUTES),
                ZoneId.systemDefault());
        List<KycVerificationEntity> stale = kycRepository
                .findByStatusAndStripeVerificationSessionIdIsNotNullAndCreatedAtBefore(
                        KycVerificationStatus.PENDING, threshold);

        for (KycVerificationEntity kyc : stale) {
            try {
                reconcileOne(kyc);
            } catch (Exception e) {
                log.warn("KYC reconciliation failed for session {}: {}",
                        kyc.getStripeVerificationSessionId(), e.getMessage());
            }
        }
    }

    @Transactional
    void reconcileOne(KycVerificationEntity kyc) {
        VerificationSession session;
        try {
            session = VerificationSession.retrieve(kyc.getStripeVerificationSessionId());
        } catch (Exception e) {
            log.warn("Could not retrieve KYC session {} from Stripe for reconciliation: {}",
                    kyc.getStripeVerificationSessionId(), e.getMessage());
            return;
        }

        String status = session.getStatus();
        // "processing" (Stripe still analyzing doc/selfie) — nothing to reconcile yet.
        if (!"verified".equals(status) && !"canceled".equals(status) && !"requires_input".equals(status)) {
            return;
        }

        UserEntity user = userRepository.findById(kyc.getUserId()).orElse(null);
        if (user == null) {
            log.warn("No user for KYC {} during reconciliation", kyc.getId());
            return;
        }
        // Guard against a race with a webhook that landed between the query and this call.
        if (kyc.getStatus() == KycVerificationStatus.VERIFIED) return;

        // `code` is Stripe's stable enum, used for French mapping — `reason` is free-text English.
        String lastErrorCode = session.getLastError() != null ? session.getLastError().getCode() : null;

        switch (status) {
            case "verified" -> {
                kyc.setStatus(KycVerificationStatus.VERIFIED);
                user.setKycStatus(KycStatus.VERIFIED);
                kycRepository.save(kyc);
                userRepository.save(user);
                auditService.log("kyc_verification", kyc.getId(), "KYC_VERIFIED_RECONCILED",
                        user.getId(), Map.of("sessionId", kyc.getStripeVerificationSessionId()));
                eventPublisher.publishEvent(new UserKycVerifiedEvent(user.getId()));
            }
            case "canceled" -> {
                kyc.setStatus(KycVerificationStatus.REJECTED);
                kyc.setRejectionReason("session_canceled");
                user.setKycStatus(KycStatus.NOT_STARTED);
                kycRepository.save(kyc);
                userRepository.save(user);
                auditService.log("kyc_verification", kyc.getId(), "KYC_CANCELED_RECONCILED",
                        user.getId(), Map.of("sessionId", kyc.getStripeVerificationSessionId(),
                                "reason", "session_canceled"));
            }
            case "requires_input" -> {
                kyc.setStatus(KycVerificationStatus.REJECTED);
                kyc.setRejectionReason(lastErrorCode != null ? lastErrorCode : "verification_failed");
                user.setKycStatus(KycStatus.REJECTED);
                kycRepository.save(kyc);
                userRepository.save(user);
                auditService.log("kyc_verification", kyc.getId(), "KYC_REJECTED_RECONCILED",
                        user.getId(), Map.of("sessionId", kyc.getStripeVerificationSessionId(),
                                "reason", kyc.getRejectionReason()));
            }
        }

        log.info("KYC session {} reconciled to Stripe status '{}' (webhook was lost)",
                kyc.getStripeVerificationSessionId(), status);
    }

}
