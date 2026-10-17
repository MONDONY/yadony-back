package com.yadony.api.gppro;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.gppro.dto.GpProApplyRequest;
import com.yadony.api.gppro.dto.GpProStatusResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GpProApplicationServiceTest {

    private static final String UID = "uid-1";

    @Mock GpProApplicationRepository applications;
    @Mock GpProDocumentRepository documents;
    @Mock GpProDocumentService documentService;
    @Mock UserRepository users;
    @Mock AuditService audit;

    GpProApplicationService service;
    UserEntity user;
    UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new GpProApplicationService(applications, documents, documentService, users, audit);
        user = new UserEntity();
        setId(user, userId);
        user.setKycStatus(KycStatus.VERIFIED);
        when(users.findByFirebaseUid(UID)).thenReturn(Optional.of(user));
        when(applications.saveAndFlush(any())).thenAnswer(inv -> {
            GpProApplicationEntity a = inv.getArgument(0);
            setId(a, UUID.randomUUID());
            return a;
        });
        when(applications.findFirstByUserIdAndStatusIn(eq(userId), anyCollection())).thenReturn(Optional.empty());
        when(applications.findFirstByUserIdAndStatusOrderByDecidedAtDesc(any(), any())).thenReturn(Optional.empty());
    }

    private static void setId(Object o, UUID id) {
        org.springframework.test.util.ReflectionTestUtils.setField(o, "id", id);
    }

    private GpProApplyRequest req(GpProApplicationSource src, List<UUID> docs) {
        return new GpProApplyRequest(GpProTravelFrequency.MONTHLY, "Mon Nom", docs, src);
    }

    private GpProApplicationEntity app(GpProApplicationStatus st) {
        GpProApplicationEntity a = new GpProApplicationEntity();
        setId(a, UUID.randomUUID());
        a.setUserId(userId);
        a.setStatus(st);
        return a;
    }

    private void assertCode(Runnable r, HttpStatus status, String code) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getErrorCode()).isEqualTo(code);
        });
    }

    @Test
    void applyWithVerifiedKycCreatesPending() {
        GpProStatusResponse r = service.apply(UID, req(GpProApplicationSource.PROFILE, List.of()));
        assertThat(r.state()).isEqualTo("PENDING");
        verify(users).findByIdForUpdate(userId);
    }

    @Test
    void applyFromSignupWithoutKycCreatesAwaitingKyc() {
        user.setKycStatus(KycStatus.NOT_STARTED);
        assertThat(service.apply(UID, req(GpProApplicationSource.SIGNUP, null)).state()).isEqualTo("AWAITING_KYC");
    }

    @Test
    void applyFromProfileWithoutKycThrows422KycRequired() {
        user.setKycStatus(KycStatus.PENDING);
        assertCode(() -> service.apply(UID, req(GpProApplicationSource.PROFILE, List.of())),
                HttpStatus.UNPROCESSABLE_ENTITY, "gp-pro-kyc-required");
    }

    @Test
    void applyWithAdminSourceThrows422() {
        assertCode(() -> service.apply(UID, req(GpProApplicationSource.ADMIN, List.of())),
                HttpStatus.UNPROCESSABLE_ENTITY, "gp-pro-invalid-source");
    }

    @Test
    void applyWhileOpenThrows409() {
        when(applications.findFirstByUserIdAndStatusIn(eq(userId), anyCollection()))
                .thenReturn(Optional.of(app(GpProApplicationStatus.PENDING)));
        assertCode(() -> service.apply(UID, req(GpProApplicationSource.PROFILE, List.of())),
                HttpStatus.CONFLICT, "gp-pro-application-open");
    }

    @Test
    void applyWhenAlreadyGpProThrows409() {
        user.setGpPro(true);
        assertCode(() -> service.apply(UID, req(GpProApplicationSource.PROFILE, List.of())),
                HttpStatus.CONFLICT, "gp-pro-already-active");
    }

    @Test
    void applyAfterRejectionIsAllowed() {
        GpProApplicationEntity rejected = app(GpProApplicationStatus.REJECTED);
        rejected.setDecidedAt(LocalDateTime.now());
        when(applications.findFirstByUserIdAndStatusOrderByDecidedAtDesc(userId, GpProApplicationStatus.REJECTED))
                .thenReturn(Optional.of(rejected));
        assertThat(service.apply(UID, req(GpProApplicationSource.PROFILE, List.of())).state()).isEqualTo("PENDING");
    }

    @Test
    void applyAfterRevocationIsAllowed() {
        user.setGpProRevokedAt(LocalDateTime.now());
        assertThat(service.apply(UID, req(GpProApplicationSource.PROFILE, List.of())).state()).isEqualTo("PENDING");
    }

    @Test
    void applyWithForeignDocumentThrows422AndAttachesNothing() {
        UUID mine = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        GpProDocumentEntity d = new GpProDocumentEntity();
        setId(d, mine);
        when(documents.findByIdInAndOwnerIdAndApplicationIdIsNull(any(Collection.class), eq(userId)))
                .thenReturn(List.of(d));
        assertCode(() -> service.apply(UID, req(GpProApplicationSource.PROFILE, List.of(mine, foreign))),
                HttpStatus.UNPROCESSABLE_ENTITY, "gp-pro-document-invalid");
        verify(applications, never()).saveAndFlush(any());
        verify(documents, never()).saveAll(any());
        assertThat(d.getApplicationId()).isNull();
    }

    @Test
    void applyAttachesOwnDocuments() {
        UUID id = UUID.randomUUID();
        GpProDocumentEntity d = new GpProDocumentEntity();
        setId(d, id);
        when(documents.findByIdInAndOwnerIdAndApplicationIdIsNull(any(Collection.class), eq(userId)))
                .thenReturn(List.of(d));
        GpProStatusResponse r = service.apply(UID, req(GpProApplicationSource.PROFILE, List.of(id)));
        assertThat(r.documentCount()).isEqualTo(1);
        assertThat(d.getApplicationId()).isNotNull();
        verify(documents).saveAll(List.of(d));
    }

    @Test
    void cancelPurgesDocumentsAndSetsCancelled() {
        GpProApplicationEntity a = app(GpProApplicationStatus.PENDING);
        when(applications.findFirstByUserIdAndStatusIn(eq(userId), anyCollection())).thenReturn(Optional.of(a));
        service.cancel(UID);
        assertThat(a.getStatus()).isEqualTo(GpProApplicationStatus.CANCELLED);
        verify(documentService).purgeAll(a.getId());
        verify(audit).log(eq("GP_PRO_APPLICATION"), eq(a.getId()), eq("GP_PRO_APPLICATION_CANCELLED"),
                eq(userId), any());
    }

    @Test
    void cancelWithoutOpenApplicationThrows404() {
        assertCode(() -> service.cancel(UID), HttpStatus.NOT_FOUND, "gp-pro-application-not-found");
    }

    @Test
    void statusIsRevokedWhenRevocationNewerThanRejection() {
        LocalDateTime now = LocalDateTime.now();
        GpProApplicationEntity rejected = app(GpProApplicationStatus.REJECTED);
        rejected.setDecidedAt(now.minusDays(5));
        rejected.setDecisionReason("refus");
        when(applications.findFirstByUserIdAndStatusOrderByDecidedAtDesc(userId, GpProApplicationStatus.REJECTED))
                .thenReturn(Optional.of(rejected));
        user.setGpProRevokedAt(now.minusDays(1));
        user.setGpProRevokedReason("retrait");
        GpProStatusResponse r = service.status(UID);
        assertThat(r.state()).isEqualTo("REVOKED");
        assertThat(r.reason()).isEqualTo("retrait");

        user.setGpProRevokedAt(now.minusDays(10));
        r = service.status(UID);
        assertThat(r.state()).isEqualTo("REJECTED");
        assertThat(r.reason()).isEqualTo("refus");
    }

    @Test
    void statusIgnoresCancelledApplications() {
        // Une demande annulée n'est ni ouverte ni rejetée : aucune requête ne la renvoie.
        assertThat(service.status(UID).state()).isEqualTo("NONE");
    }

    @Test
    void statusIsActiveWhenGpPro() {
        user.setGpPro(true);
        assertThat(service.status(UID).state()).isEqualTo("ACTIVE");
    }

    @Test
    void auditPayloadNeverContainsCommercialName() {
        service.apply(UID, req(GpProApplicationSource.PROFILE, List.of()));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(audit).log(eq("GP_PRO_APPLICATION"), any(), eq("GP_PRO_APPLIED"), eq(userId), cap.capture());
        assertThat(cap.getValue()).containsOnlyKeys("source", "frequency", "documentCount");
        assertThat(cap.getValue().values().toString()).doesNotContain("Mon Nom");
    }
}
