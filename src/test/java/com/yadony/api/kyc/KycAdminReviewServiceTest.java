package com.yadony.api.kyc;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditLogEntity;
import com.yadony.api.common.AuditLogRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.kyc.dto.KycAdminStatusResponse;
import com.yadony.api.kyc.dto.KycHistoryEntry;
import com.yadony.api.kyc.provider.VerificationProviderKind;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("KycAdminReviewService — décisions et fiche enrichie")
class KycAdminReviewServiceTest {

    @Mock KycRepository kycRepository;
    @Mock UserRepository userRepository;
    @Mock KycAdminService kycAdminService;
    @Mock KycStatusTransitionService transitions;
    @Mock AuditLogRepository auditLogRepository;
    @Mock AdminEmailDirectory adminDirectory;
    @Mock FirebaseContactService firebaseContact;
    @Mock EntityManager em;

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final String REASON = "Pièce contrôlée à la main";

    KycAdminReviewService service;
    UserEntity user;
    KycVerificationEntity kyc;

    @BeforeEach
    void setUp() {
        service = new KycAdminReviewService(kycRepository, userRepository, kycAdminService, transitions,
                auditLogRepository, adminDirectory, firebaseContact, em,
                "https://dashboard.stripe.com/identity/verification-sessions/{sessionId}", "");

        user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setKycStatus(KycStatus.PENDING);
        lenient().when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        kyc = new KycVerificationEntity();
        ReflectionTestUtils.setField(kyc, "id", UUID.randomUUID());
        kyc.setUserId(user.getId());
        kyc.setVerificationSessionId("sess_1");
        kyc.setProvider(VerificationProviderKind.DIDIT);
        kyc.setStatus(KycVerificationStatus.PENDING);
        lenient().when(kycRepository.findByUserId(user.getId())).thenReturn(Optional.of(kyc));
    }

    private static void assertProblem(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(status);
                    assertThat(e.getErrorCode()).isEqualTo(code);
                });
    }

    // ── approve ───────────────────────────────────────────────────────────────

    @Test
    void approve_passeParLaTransitionAdmin() {
        service.approve(user.getId(), ADMIN_ID, REASON);

        verify(transitions).approveByAdmin(kyc, user, ADMIN_ID, REASON);
    }

    @Test
    void approve_ligneDejaVerifiee_409() {
        kyc.setStatus(KycVerificationStatus.VERIFIED);

        assertProblem(() -> service.approve(user.getId(), ADMIN_ID, REASON),
                HttpStatus.CONFLICT, "kyc-already-verified");
        verifyNoInteractions(transitions);
    }

    @Test
    void approve_sansLigne_422() {
        when(kycRepository.findByUserId(user.getId())).thenReturn(Optional.empty());

        assertProblem(() -> service.approve(user.getId(), ADMIN_ID, REASON),
                HttpStatus.UNPROCESSABLE_ENTITY, "kyc-no-provider-session");
    }

    @Test
    void approve_sansSessionFournisseur_422() {
        kyc.setVerificationSessionId(null);

        assertProblem(() -> service.approve(user.getId(), ADMIN_ID, REASON),
                HttpStatus.UNPROCESSABLE_ENTITY, "kyc-no-provider-session");
        verifyNoInteractions(transitions);
    }

    @Test
    void approve_utilisateurInconnu_404() {
        UUID unknown = UUID.randomUUID();
        when(userRepository.findById(unknown)).thenReturn(Optional.empty());

        assertProblem(() -> service.approve(unknown, ADMIN_ID, REASON), HttpStatus.NOT_FOUND, "user-not-found");
    }

    // ── reject ────────────────────────────────────────────────────────────────

    @Test
    void reject_codeHorsCatalogue_400() {
        assertProblem(() -> service.reject(user.getId(), ADMIN_ID, "tete_pas_revenue", REASON),
                HttpStatus.BAD_REQUEST, "kyc-reject-code-invalid");
        verifyNoInteractions(transitions);
    }

    @Test
    void reject_passeParLaTransitionAdmin() {
        service.reject(user.getId(), ADMIN_ID, "selfie_face_mismatch", REASON);

        verify(transitions).rejectByAdmin(kyc, user, ADMIN_ID, "selfie_face_mismatch", REASON);
    }

    @Test
    void reject_ligneVerifiee_409_utiliserRevoke() {
        kyc.setStatus(KycVerificationStatus.VERIFIED);

        assertProblem(() -> service.reject(user.getId(), ADMIN_ID, "selfie_face_mismatch", REASON),
                HttpStatus.CONFLICT, "kyc-already-verified");
    }

    @Test
    void reject_sansSession_422() {
        kyc.setVerificationSessionId(null);

        assertProblem(() -> service.reject(user.getId(), ADMIN_ID, "selfie_face_mismatch", REASON),
                HttpStatus.UNPROCESSABLE_ENTITY, "kyc-no-provider-session");
    }

    // ── revoke ────────────────────────────────────────────────────────────────

    @Test
    void revoke_ligneVerifiee_passeParLaTransitionAdmin() {
        kyc.setStatus(KycVerificationStatus.VERIFIED);
        user.setKycStatus(KycStatus.VERIFIED);

        service.revoke(user.getId(), ADMIN_ID, "document_unverified_other", "Pièce déclarée volée, vérifiée");

        verify(transitions).revokeByAdmin(kyc, user, ADMIN_ID, "document_unverified_other",
                "Pièce déclarée volée, vérifiée");
    }

    @Test
    void revoke_ligneNonVerifiee_409() {
        assertProblem(() -> service.revoke(user.getId(), ADMIN_ID, "document_unverified_other", REASON),
                HttpStatus.CONFLICT, "kyc-not-verified");
        verifyNoInteractions(transitions);
    }

    @Test
    void revoke_sansLigneNiVerification_409() {
        when(kycRepository.findByUserId(user.getId())).thenReturn(Optional.empty());

        assertProblem(() -> service.revoke(user.getId(), ADMIN_ID, "document_unverified_other", REASON),
                HttpStatus.CONFLICT, "kyc-not-verified");
    }

    @Test
    void revoke_compteVerifieSansLigne_422() {
        when(kycRepository.findByUserId(user.getId())).thenReturn(Optional.empty());
        user.setKycStatus(KycStatus.VERIFIED);

        assertProblem(() -> service.revoke(user.getId(), ADMIN_ID, "document_unverified_other", REASON),
                HttpStatus.UNPROCESSABLE_ENTITY, "kyc-no-provider-session");
    }

    @Test
    void revoke_codeHorsCatalogue_400() {
        assertProblem(() -> service.revoke(user.getId(), ADMIN_ID, "nope", REASON),
                HttpStatus.BAD_REQUEST, "kyc-reject-code-invalid");
    }

    // ── detail ────────────────────────────────────────────────────────────────

    private static AuditLogEntity audit(String action, UUID actor, Map<String, Object> payload, LocalDateTime at) {
        AuditLogEntity entry = new AuditLogEntity();
        entry.setAction(action);
        entry.setActorId(actor);
        entry.setPayload(payload);
        ReflectionTestUtils.setField(entry, "createdAt", at);
        return entry;
    }

    private static KycAdminStatusResponse base(UUID userId, String sessionId, String provider) {
        return new KycAdminStatusResponse(userId, "REJECTED", "REJECTED", "selfie_face_mismatch",
                "selfie_face_mismatch", sessionId, "Declined", null, null, null, false, provider);
    }

    @Test
    void detail_ajouteDecisionHistoriqueEtLienConsole() {
        LocalDateTime decidedAt = LocalDateTime.of(2026, 9, 20, 10, 0);
        kyc.setProvider(VerificationProviderKind.STRIPE);
        kyc.setVerificationSessionId("vs_1");
        kyc.setDecisionKind(KycDecisionKind.REJECTED);
        kyc.setDecidedByAdminId(ADMIN_ID);
        kyc.setDecidedAt(decidedAt);
        kyc.setDecisionReason("Selfie flou");
        when(kycAdminService.getForUser(user.getId())).thenReturn(base(user.getId(), "vs_1", "STRIPE"));
        when(auditLogRepository.findKycHistory(any(), any())).thenReturn(List.of(
                audit("KYC_REJECTED_BY_ADMIN", ADMIN_ID,
                        Map.of("code", "selfie_face_mismatch", "reason", "Selfie flou"), decidedAt),
                audit("KYC_IN_REVIEW", user.getId(), Map.of("sessionId", "vs_1"), decidedAt.minusHours(2)),
                audit("KYC_SESSION_CREATED", user.getId(), Map.of("provider", "STRIPE"), decidedAt.minusHours(3)),
                audit("KYC_ALIEN_ACTION", null, null, decidedAt.minusHours(4))));
        when(adminDirectory.emailsOf(any())).thenReturn(Map.of(ADMIN_ID, "admin@yadony.test"));

        KycAdminStatusResponse resp = service.detail(user.getId());

        assertThat(resp.kycStatus()).isEqualTo("REJECTED");
        assertThat(resp.stripeSessionId()).isEqualTo("vs_1");
        assertThat(resp.decisionKind()).isEqualTo("REJECTED");
        assertThat(resp.decidedAt()).isEqualTo(decidedAt);
        assertThat(resp.decidedByAdminEmail()).isEqualTo("admin@yadony.test");
        assertThat(resp.decisionReason()).isEqualTo("Selfie flou");
        assertThat(resp.providerSessionUrl())
                .isEqualTo("https://dashboard.stripe.com/identity/verification-sessions/vs_1");

        List<KycHistoryEntry> history = resp.history();
        assertThat(history).extracting(KycHistoryEntry::actorKind)
                .containsExactly("ADMIN", "PROVIDER", "USER", "SYSTEM");
        assertThat(history.get(0).actorEmail()).isEqualTo("admin@yadony.test");
        assertThat(history.get(0).detail()).isEqualTo("selfie_face_mismatch : Selfie flou");
        assertThat(history.get(1).actorEmail()).isNull();
        assertThat(history.get(2).detail()).isEqualTo("STRIPE");
        assertThat(history.get(3).detail()).isNull();
    }

    @Test
    void detail_didit_sansModeleDUrl_rendUnLienNul() {
        when(kycAdminService.getForUser(user.getId())).thenReturn(base(user.getId(), "sess_1", "DIDIT"));
        when(auditLogRepository.findKycHistory(any(), any())).thenReturn(List.of());
        lenient().when(adminDirectory.emailsOf(any())).thenReturn(Map.of());

        KycAdminStatusResponse resp = service.detail(user.getId());

        assertThat(resp.providerSessionUrl()).isNull();
        assertThat(resp.decisionKind()).isNull();
        assertThat(resp.decidedByAdminEmail()).isNull();
        assertThat(resp.history()).isEmpty();
    }

    @Test
    void detail_sansLigne_historiqueVideEtAucuneDecision() {
        when(kycRepository.findByUserId(user.getId())).thenReturn(Optional.empty());
        when(kycAdminService.getForUser(user.getId())).thenReturn(new KycAdminStatusResponse(user.getId(),
                "NOT_STARTED", "NOT_STARTED", null, null, null, null, null, null, null, false, null));

        KycAdminStatusResponse resp = service.detail(user.getId());

        assertThat(resp.history()).isEmpty();
        assertThat(resp.providerSessionUrl()).isNull();
        verifyNoInteractions(auditLogRepository);
    }

    // ── masquage du téléphone ────────────────────────────────────────────────

    @Test
    void maskPhone_neGardeQueLesQuatreDerniersChiffres() {
        assertThat(KycAdminReviewService.maskPhone("+33612345678")).isEqualTo("•••• 5678");
        assertThat(KycAdminReviewService.maskPhone("+221 77 000 12 34")).isEqualTo("•••• 1234");
        assertThat(KycAdminReviewService.maskPhone("123")).isEqualTo("••••");
        assertThat(KycAdminReviewService.maskPhone(null)).isNull();
        assertThat(KycAdminReviewService.maskPhone(" ")).isNull();
    }

    @Test
    void consoleUrl_remplaceLIdentifiantEncode() {
        assertThat(KycAdminReviewService.consoleUrl("https://x/{sessionId}/view", "a b")).isEqualTo("https://x/a+b/view");
        assertThat(KycAdminReviewService.consoleUrl("", "vs_1")).isNull();
        assertThat(KycAdminReviewService.consoleUrl("https://x/{sessionId}", null)).isNull();
        verifyNoInteractions(firebaseContact);
    }
}
