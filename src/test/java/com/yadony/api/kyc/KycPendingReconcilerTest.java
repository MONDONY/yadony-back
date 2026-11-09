package com.yadony.api.kyc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.kyc.provider.VerificationProviderKind;
import com.yadony.api.kyc.provider.didit.DiditClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KycPendingReconcilerTest {

    private static final String SESSION_ID = "sess_1";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock KycRepository kycRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock AdminAlertService adminAlert;
    @Mock KycRefusedSessionRegistry refusedSessions;
    @Mock DiditClient didit;
    @Mock AdminAlertEscalator alerts;

    private KycPendingReconciler reconciler;
    private KycVerificationEntity kyc;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        KycStatusTransitionService transitions = new KycStatusTransitionService(
                kycRepository, userRepository, auditService, eventPublisher, adminAlert, refusedSessions);
        reconciler = new KycPendingReconciler(kycRepository, userRepository, transitions, didit, alerts,
                TransactionOperations.withoutTransaction());
        ReflectionTestUtils.setField(reconciler, "enabled", true);
        ReflectionTestUtils.setField(reconciler, "minAge", Duration.ofMinutes(30));
        ReflectionTestUtils.setField(reconciler, "maxAge", Duration.ofDays(3));
        ReflectionTestUtils.setField(reconciler, "batchSize", 20);
        ReflectionTestUtils.setField(reconciler, "reviewAlertAfter", Duration.ofHours(2));
        ReflectionTestUtils.setField(reconciler, "unreadableAlertAfter", Duration.ofHours(2));

        user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setKycStatus(KycStatus.PENDING);

        kyc = new KycVerificationEntity();
        ReflectionTestUtils.setField(kyc, "id", UUID.randomUUID());
        kyc.setUserId(user.getId());
        kyc.setProvider(VerificationProviderKind.DIDIT);
        kyc.setStatus(KycVerificationStatus.PENDING);
        kyc.setVerificationSessionId(SESSION_ID);
        setUpdatedAt(Duration.ofHours(5));

        lenient().when(kycRepository.findById(kyc.getId())).thenReturn(Optional.of(kyc));
        lenient().when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
    }

    private void setUpdatedAt(Duration ago) {
        ReflectionTestUtils.setField(kyc, "updatedAt", LocalDateTime.now(ZoneOffset.UTC).minus(ago));
    }

    private void staleCandidate() {
        when(kycRepository.findStalePending(eq(VerificationProviderKind.DIDIT),
                any(LocalDateTime.class), any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(kyc));
    }

    private void diditSays(String json) throws Exception {
        JsonNode node = JSON.readTree(json);
        when(didit.retrieveDecision(SESSION_ID)).thenReturn(Optional.of(node));
    }

    @Test
    void approved_webhookPerdu_marqueVerifieEtCloseAlerte() throws Exception {
        staleCandidate();
        diditSays("{\"status\":\"Approved\"}");

        assertThat(reconciler.reconcileSessions()).isEqualTo(1);

        assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.VERIFIED);
        assertThat(user.getKycStatus()).isEqualTo(KycStatus.VERIFIED);
        verify(alerts).resolveOpen(KycPendingReconciler.alertType(user.getId()));
    }

    @Test
    void declined_litLesAvertissementsALaRacineDeLaDecision() throws Exception {
        staleCandidate();
        diditSays("{\"status\":\"Declined\",\"warnings\":[{\"risk\":\"DOCUMENT_EXPIRED\","
                + "\"short_description\":\"Pièce expirée\"}]}");

        assertThat(reconciler.reconcileSessions()).isEqualTo(1);

        assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.REJECTED);
        assertThat(kyc.getRejectionCode()).isEqualTo("DOCUMENT_EXPIRED");
        assertThat(kyc.getRejectionReason()).isEqualTo("Pièce expirée");
    }

    @Test
    void abandoned_remetLUtilisateurEnEtatDeRecommencer() throws Exception {
        staleCandidate();
        diditSays("{\"status\":\"Abandoned\"}");

        assertThat(reconciler.reconcileSessions()).isEqualTo(1);

        assertThat(user.getKycStatus()).isEqualTo(KycStatus.NOT_STARTED);
        assertThat(kyc.getSubmittedAt()).isNull();
    }

    @Test
    void inReview_posesLaDateDeSoumissionUneSeuleFois() throws Exception {
        staleCandidate();
        diditSays("{\"status\":\"In Review\"}");

        assertThat(reconciler.reconcileSessions()).isEqualTo(1);
        LocalDateTime first = kyc.getSubmittedAt();
        assertThat(first).isNotNull();
        assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.PENDING);
        verify(alerts, never()).resolveOpen(anyString());

        // Deuxieme passage : deja en revue, rien a rejouer (ni ecriture ni audit).
        org.mockito.Mockito.clearInvocations(auditService, kycRepository);
        assertThat(reconciler.reconcileSessions()).isZero();
        assertThat(kyc.getSubmittedAt()).isEqualTo(first);
        verify(auditService, never()).log(anyString(), any(), anyString(), any(), any());
    }

    @Test
    void parcoursEnCours_nAChangeRien() throws Exception {
        staleCandidate();
        diditSays("{\"status\":\"In Progress\"}");

        assertThat(reconciler.reconcileSessions()).isZero();

        assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.PENDING);
        assertThat(user.getKycStatus()).isEqualTo(KycStatus.PENDING);
        verify(kycRepository, never()).save(any());
    }

    @Test
    void webhookConcurrent_laLigneDejaVerifieeNEstPasTouchee() throws Exception {
        staleCandidate();
        diditSays("{\"status\":\"Declined\"}");
        kyc.setStatus(KycVerificationStatus.VERIFIED);

        assertThat(reconciler.reconcileSessions()).isZero();

        assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.VERIFIED);
        verify(kycRepository, never()).save(any());
    }

    @Test
    void sessionRemplaceeEntreTemps_estIgnoree() throws Exception {
        staleCandidate();
        JsonNode approved = JSON.readTree("{\"status\":\"Approved\"}");
        // La session change pendant l'appel Didit (hors transaction) : l'application doit s'abstenir.
        when(didit.retrieveDecision(SESSION_ID)).thenAnswer(call -> {
            kyc.setVerificationSessionId("sess_2");
            return Optional.of(approved);
        });

        assertThat(reconciler.reconcileSessions()).isZero();
        assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.PENDING);
    }

    @Test
    void utilisateurIntrouvable_nEstPasRattrape() throws Exception {
        staleCandidate();
        diditSays("{\"status\":\"Approved\"}");
        when(userRepository.findById(user.getId())).thenReturn(Optional.empty());

        assertThat(reconciler.reconcileSessions()).isZero();
        assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.PENDING);
    }

    @Test
    void didit_illisibleDepuisPlusDeDeuxHeures_leveUneAlerteParUtilisateur() {
        staleCandidate();
        when(didit.retrieveDecision(SESSION_ID)).thenReturn(Optional.empty());

        assertThat(reconciler.reconcileSessions()).isZero();

        verify(alerts).raiseOnce(eq(KycPendingReconciler.alertType(user.getId())), eq("WARN"),
                anyString(), any());
    }

    @Test
    void didit_illisibleMaisRecent_neLevePasDAlerte() {
        setUpdatedAt(Duration.ofMinutes(45));
        staleCandidate();
        when(didit.retrieveDecision(SESSION_ID)).thenReturn(Optional.empty());

        assertThat(reconciler.reconcileSessions()).isZero();

        verify(alerts, never()).raiseOnce(anyString(), anyString(), anyString(), any());
    }

    @Test
    void uneErreurSurUneLigne_nArretePasLesAutres() throws Exception {
        KycVerificationEntity other = new KycVerificationEntity();
        ReflectionTestUtils.setField(other, "id", UUID.randomUUID());
        other.setUserId(UUID.randomUUID());
        other.setVerificationSessionId("sess_boom");
        when(kycRepository.findStalePending(any(), any(), any(), any())).thenReturn(List.of(other, kyc));
        when(didit.retrieveDecision("sess_boom")).thenThrow(new IllegalStateException("boom"));
        diditSays("{\"status\":\"Approved\"}");

        assertThat(reconciler.reconcileSessions()).isEqualTo(1);
        assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.VERIFIED);
    }

    @Test
    void revueDidit_enAttenteTropLongtemps_leveUneAlerteParLigne() {
        when(kycRepository.findInReviewSince(eq(VerificationProviderKind.DIDIT),
                any(LocalDateTime.class), any(Pageable.class))).thenReturn(List.of(kyc));

        assertThat(reconciler.alertStuckReviews()).isEqualTo(1);

        verify(alerts).raiseOnce(eq("KYC_PENDING_" + user.getId()), eq("WARN"), anyString(), any());
    }

    @Test
    void alerteImpossibleALever_neCasseNiLeRattrapageNiLaBoucle() {
        when(kycRepository.findInReviewSince(any(), any(), any())).thenReturn(List.of(kyc));
        when(alerts.raiseOnce(anyString(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("base indisponible"));

        assertThat(reconciler.alertStuckReviews()).isEqualTo(1);
    }

    @Test
    void run_inactif_neFaitRien() {
        ReflectionTestUtils.setField(reconciler, "enabled", false);

        reconciler.run();

        org.mockito.Mockito.verifyNoInteractions(kycRepository, didit, alerts);
    }

    @Test
    void run_avaleLesErreursDesDeuxEtapes() {
        when(kycRepository.findStalePending(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("base"));
        when(kycRepository.findInReviewSince(any(), any(), any()))
                .thenThrow(new IllegalStateException("base"));

        reconciler.run();

        verify(kycRepository).findInReviewSince(any(), any(), any());
    }

    @Test
    void typeAlerte_tientDansLaColonneAdminAlerts() {
        assertThat(KycPendingReconciler.alertType(UUID.randomUUID()).length())
                .isLessThanOrEqualTo(AdminAlertEscalator.TYPE_MAX_LENGTH);
    }
}
