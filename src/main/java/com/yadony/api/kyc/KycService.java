package com.yadony.api.kyc;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyNotFoundException;
import com.yadony.api.kyc.dto.KycSessionResponse;
import com.yadony.api.kyc.dto.KycStatusResponse;
import com.yadony.api.kyc.provider.IdentityProviderResolver;
import com.yadony.api.kyc.provider.IdentityVerificationProvider;
import com.yadony.api.kyc.provider.ProviderSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final IdentityProviderResolver providers;

    public KycService(KycRepository kycRepository,
                      UserRepository userRepository,
                      AuditService auditService,
                      IdentityProviderResolver providers) {
        this.kycRepository = kycRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.providers = providers;
    }

    @Transactional
    public KycSessionResponse createSession(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyNotFoundException("Utilisateur introuvable"));

        if (user.getKycStatus() == KycStatus.VERIFIED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "KYC déjà vérifié");
        }

        IdentityVerificationProvider provider = providers.forCreation();
        Optional<KycVerificationEntity> existing = kycRepository.findByUserId(user.getId());

        // Une session ne se reprend que chez le fournisseur qui l'a produite : apres une
        // bascule, l'identifiant de l'ancienne session ne veut plus rien dire pour le nouveau.
        // La reutilisation elle-meme appartient au fournisseur — Stripe la teste a la main,
        // Didit la fait seul.
        String resumableSessionId = existing
                .filter(kyc -> kyc.getProvider() == provider.kind())
                .map(KycVerificationEntity::getVerificationSessionId)
                .orElse(null);

        // Transition NOT_STARTED → PENDING when session is created
        if (user.getKycStatus() == KycStatus.NOT_STARTED) {
            user.setKycStatus(KycStatus.PENDING);
            userRepository.save(user);
        }

        ProviderSession session = provider.createSession(user, resumableSessionId);

        if (session.sessionId().equals(resumableSessionId)) {
            // Session reprise telle quelle : rien a reecrire en base.
            return new KycSessionResponse(session.url(), session.sessionId(), "PENDING");
        }

        KycVerificationEntity kyc = existing.orElseGet(() -> {
            KycVerificationEntity newKyc = new KycVerificationEntity();
            newKyc.setUserId(user.getId());
            return newKyc;
        });

        kyc.setVerificationSessionId(session.sessionId());
        kyc.setProvider(provider.kind());
        kyc.setStatus(KycVerificationStatus.PENDING);
        kyc.setRejectionReason(null);
        kyc.setRejectionCode(null);
        kycRepository.save(kyc);

        auditService.log("kyc_verification", kyc.getId(), "KYC_SESSION_CREATED",
                user.getId(), Map.of("sessionId", session.sessionId(),
                        "provider", provider.kind().name()));

        return new KycSessionResponse(session.url(), session.sessionId(), "PENDING");
    }

    @Transactional
    public void abandonSession(String firebaseUid) {
        UserEntity user = userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new YadonyNotFoundException("Utilisateur introuvable"));

        if (user.getKycStatus() != KycStatus.PENDING) return;

        user.setKycStatus(KycStatus.NOT_STARTED);
        userRepository.save(user);

        // Best-effort, et chez le fournisseur de la ligne : l'abandon local ne doit jamais
        // dependre du distant. Didit n'a pas d'annulation — sa session inachevee sera
        // resservie au prochain demarrage, ce qui est le comportement voulu.
        kycRepository.findByUserId(user.getId()).ifPresent(kyc ->
                providers.forRecord(kyc.getProvider())
                        .ifPresent(provider -> provider.abandonSession(kyc.getVerificationSessionId())));

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
