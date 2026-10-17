package com.yadony.api.gppro;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.StorageService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.disputes.DisputeRepository;
import com.yadony.api.gppro.events.GpProDecisionEvent;
import com.yadony.api.gppro.events.GpProStatusChangedEvent;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.ratings.RatingRepository;
import com.yadony.api.signalements.ReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GpProAdminServiceTest {

    @Mock GpProApplicationRepository applications;
    @Mock GpProDocumentRepository documents;
    @Mock GpProReviewFlagRepository flags;
    @Mock UserRepository users;
    @Mock AnnouncementRepository announcements;
    @Mock BidRepository bids;
    @Mock RatingRepository ratings;
    @Mock DisputeRepository disputes;
    @Mock ReportRepository reports;
    @Mock StorageService storage;
    @Mock AuditService audit;
    @Mock ApplicationEventPublisher events;

    GpProAdminService service;
    UserEntity user;
    UUID userId = UUID.randomUUID();
    UUID adminId = UUID.randomUUID();
    UUID appId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        GpProDocumentService documentService = new GpProDocumentService(documents, users, storage);
        service = new GpProAdminService(applications, documents, documentService, flags, users,
                announcements, bids, ratings, disputes, reports, storage, audit, events);
        user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setKycStatus(KycStatus.VERIFIED);
        user.setStatus(UserStatus.ACTIVE);
        user.setGpProRevokedAt(LocalDateTime.now().minusDays(3));
        user.setGpProRevokedReason("ancien retrait");
        when(users.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(applications.findById(appId)).thenReturn(Optional.of(application(GpProApplicationStatus.PENDING)));
        when(applications.decide(eq(appId), any(), eq(adminId), any(), any())).thenReturn(1);
        when(applications.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private GpProApplicationEntity application(GpProApplicationStatus status) {
        GpProApplicationEntity a = new GpProApplicationEntity();
        ReflectionTestUtils.setField(a, "id", appId);
        a.setUserId(userId);
        a.setStatus(status);
        a.setSource(GpProApplicationSource.PROFILE);
        return a;
    }

    private GpProDocumentEntity document(String key) {
        GpProDocumentEntity d = new GpProDocumentEntity();
        ReflectionTestUtils.setField(d, "id", UUID.randomUUID());
        d.setApplicationId(appId);
        d.setOwnerId(userId);
        d.setDocType(GpProDocumentType.TRAVEL_PROOF);
        d.setStorageKey(key);
        return d;
    }

    private void assertCode(Runnable r, HttpStatus status, String code) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getErrorCode()).isEqualTo(code);
        });
    }

    @Test
    void approveSetsGpProPurgesAndPublishes() {
        GpProDocumentEntity doc = document("gp_pro/u/a.jpg");
        when(documents.findByApplicationIdAndPurgedAtIsNull(appId)).thenReturn(List.of(doc));

        service.approve(appId, adminId);

        assertThat(user.isGpPro()).isTrue();
        assertThat(user.getGpProGrantedAt()).isNotNull();
        assertThat(user.getGpProRevokedAt()).isNull();
        assertThat(user.getGpProRevokedReason()).isNull();
        verify(storage).deleteFile("gp_pro/u/a.jpg");
        assertThat(doc.getStorageKey()).isNull();
        assertThat(doc.getPurgedAt()).isNotNull();
        verify(audit).log(eq("GP_PRO_APPLICATION"), eq(appId), eq("GP_PRO_APPROVED"), eq(adminId), any());
        verify(audit).log(eq("GP_PRO_APPLICATION"), eq(appId), eq("GP_PRO_DOCUMENTS_PURGED"), eq(adminId), any());
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events, org.mockito.Mockito.times(2)).publishEvent(published.capture());
        assertThat(published.getAllValues()).containsExactly(
                new GpProDecisionEvent(userId, GpProDecisionEvent.Kind.APPROVED, null),
                new GpProStatusChangedEvent(userId, true));
    }

    @Test
    void approveWhenAlreadyDecidedThrows409() {
        when(applications.decide(eq(appId), any(), any(), any(), any())).thenReturn(0);

        assertCode(() -> service.approve(appId, adminId), HttpStatus.CONFLICT, "gp-pro-application-not-pending");

        assertThat(user.isGpPro()).isFalse();
        verify(events, never()).publishEvent(any(Object.class));
        verify(storage, never()).deleteFile(anyString());
    }

    @Test
    void approveKeepsDecisionWhenPurgeFails() {
        GpProDocumentEntity doc = document("gp_pro/u/a.jpg");
        when(documents.findByApplicationIdAndPurgedAtIsNull(appId)).thenReturn(List.of(doc));
        doThrow(new RuntimeException("R2 indisponible")).when(storage).deleteFile("gp_pro/u/a.jpg");

        service.approve(appId, adminId);

        assertThat(user.isGpPro()).isTrue();
        assertThat(doc.getStorageKey()).isEqualTo("gp_pro/u/a.jpg");
        assertThat(doc.getPurgedAt()).isNull();
        verify(events, org.mockito.Mockito.times(2)).publishEvent(any(Object.class));
    }

    @Test
    void rejectRequiresReasonAndPublishesDecisionOnly() {
        service.reject(appId, adminId, "Justificatifs illisibles");

        verify(applications).decide(eq(appId), eq(GpProApplicationStatus.REJECTED), eq(adminId),
                eq("Justificatifs illisibles"), any());
        assertThat(user.isGpPro()).isFalse();
        verify(audit).log(eq("GP_PRO_APPLICATION"), eq(appId), eq("GP_PRO_REJECTED"), eq(adminId), any());
        verify(events).publishEvent(new GpProDecisionEvent(userId, GpProDecisionEvent.Kind.REJECTED,
                "Justificatifs illisibles"));
        verify(events, org.mockito.Mockito.times(1)).publishEvent(any(Object.class));
    }

    @Test
    void grantWithoutKycThrows422() {
        user.setKycStatus(KycStatus.PENDING);

        assertCode(() -> service.grant(userId, adminId, "note"),
                HttpStatus.UNPROCESSABLE_ENTITY, "gp-pro-kyc-required");

        assertThat(user.isGpPro()).isFalse();
        verify(applications, never()).saveAndFlush(any());
    }

    @Test
    void grantWhenAlreadyActiveThrows409() {
        user.setGpPro(true);

        assertCode(() -> service.grant(userId, adminId, null), HttpStatus.CONFLICT, "gp-pro-already-active");
    }

    @Test
    void grantClosesOpenApplication() {
        GpProApplicationEntity open = application(GpProApplicationStatus.PENDING);
        when(applications.findFirstByUserIdAndStatusIn(eq(userId), any())).thenReturn(Optional.of(open));

        service.grant(userId, adminId, "  connu du terrain ");

        assertThat(open.getStatus()).isEqualTo(GpProApplicationStatus.APPROVED);
        assertThat(open.getSource()).isEqualTo(GpProApplicationSource.PROFILE);
        assertThat(open.getDecidedBy()).isEqualTo(adminId);
        assertThat(open.getDecisionReason()).isEqualTo("connu du terrain");
        assertThat(user.isGpPro()).isTrue();
        verify(audit).log(eq("GP_PRO_APPLICATION"), eq(appId), eq("GP_PRO_GRANTED_DIRECTLY"), eq(adminId), any());
        verify(events, org.mockito.Mockito.times(2)).publishEvent(any(Object.class));
    }

    @Test
    void grantWithoutOpenApplicationCreatesAdminApplication() {
        when(applications.findFirstByUserIdAndStatusIn(eq(userId), any())).thenReturn(Optional.empty());

        service.grant(userId, adminId, "geste commercial");

        ArgumentCaptor<GpProApplicationEntity> saved = ArgumentCaptor.forClass(GpProApplicationEntity.class);
        verify(applications).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getSource()).isEqualTo(GpProApplicationSource.ADMIN);
        assertThat(saved.getValue().getStatus()).isEqualTo(GpProApplicationStatus.APPROVED);
        assertThat(saved.getValue().getUserId()).isEqualTo(userId);
        assertThat(saved.getValue().getDecisionReason()).isEqualTo("geste commercial");
        assertThat(saved.getValue().getTravelFrequency()).isNull();
    }

    @Test
    void revokeClearsFlagsAndStoresReason() {
        user.setGpPro(true);
        user.setGpProRevokedAt(null);
        GpProReviewFlagEntity flag = new GpProReviewFlagEntity();
        when(flags.findByUserIdAndResolvedAtIsNull(userId)).thenReturn(List.of(flag));

        service.revoke(userId, adminId, "Trois litiges ouverts");

        assertThat(user.isGpPro()).isFalse();
        assertThat(user.getGpProRevokedAt()).isNotNull();
        assertThat(user.getGpProRevokedReason()).isEqualTo("Trois litiges ouverts");
        assertThat(flag.getResolvedAt()).isNotNull();
        verify(flags).saveAll(List.of(flag));
        verify(audit).log(eq("USER"), eq(userId), eq("GP_PRO_REVOKED"), eq(adminId), any());
        verify(events).publishEvent(new GpProDecisionEvent(userId, GpProDecisionEvent.Kind.REVOKED,
                "Trois litiges ouverts"));
        verify(events).publishEvent(new GpProStatusChangedEvent(userId, false));
    }

    @Test
    void revokeWhenNotGpProThrows409() {
        assertCode(() -> service.revoke(userId, adminId, "Motif suffisant"),
                HttpStatus.CONFLICT, "gp-pro-not-active");

        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void approveUnknownApplicationThrows404() {
        UUID other = UUID.randomUUID();
        when(applications.findById(other)).thenReturn(Optional.empty());

        assertCode(() -> service.approve(other, adminId), HttpStatus.NOT_FOUND, "gp-pro-application-not-found");
    }

    @Test
    void approveWithoutKycThrows422AndLeavesPending() {
        user.setKycStatus(KycStatus.PENDING);

        assertCode(() -> service.approve(appId, adminId),
                HttpStatus.UNPROCESSABLE_ENTITY, "gp-pro-kyc-required");

        verify(applications, never()).decide(any(), any(), any(), any(), any());
        assertThat(user.isGpPro()).isFalse();
    }

    @Test
    void approveSuspendedUserThrows409() {
        user.setStatus(UserStatus.SUSPENDED);

        assertCode(() -> service.approve(appId, adminId), HttpStatus.CONFLICT, "gp-pro-user-not-eligible");

        verify(applications, never()).decide(any(), any(), any(), any(), any());
    }

    @Test
    void approveUserWithPendingDeletionThrows409() {
        user.setDeletionRequestedAt(java.time.Instant.now());

        assertCode(() -> service.approve(appId, adminId), HttpStatus.CONFLICT, "gp-pro-user-not-eligible");
    }

    @Test
    void grantBannedUserThrows409() {
        user.setStatus(UserStatus.BANNED);

        assertCode(() -> service.grant(userId, adminId, null), HttpStatus.CONFLICT, "gp-pro-user-not-eligible");
    }

    @Test
    void rejectAndRevokeRejectBlankReason() {
        user.setGpPro(true);

        assertCode(() -> service.reject(appId, adminId, "   "),
                HttpStatus.UNPROCESSABLE_ENTITY, "gp-pro-reason-required");
        assertCode(() -> service.revoke(userId, adminId, null),
                HttpStatus.UNPROCESSABLE_ENTITY, "gp-pro-reason-required");
        verify(applications, never()).decide(any(), any(), any(), any(), any());
    }

    @Test
    void rejectTrimsReason() {
        service.reject(appId, adminId, "  Justificatifs illisibles  ");

        verify(applications).decide(eq(appId), eq(GpProApplicationStatus.REJECTED), eq(adminId),
                eq("Justificatifs illisibles"), any());
    }

    @Test
    void detailCountsDeliveredParcelsNotTrips() {
        when(users.findById(userId)).thenReturn(Optional.of(user));
        when(bids.countByAnnouncementTravelerIdAndStatus(userId, BidStatus.COMPLETED)).thenReturn(7L);
        when(announcements.countByTravelerIdAndStatus(any(), any())).thenReturn(99L);

        assertThat(service.detail(appId).completedDeliveries()).isEqualTo(7L);
    }

    @Test
    void dismissFlagMarksDismissedAndAudits() {
        UUID flagId = UUID.randomUUID();
        GpProReviewFlagEntity flag = new GpProReviewFlagEntity();
        flag.setUserId(userId);
        flag.setReason(GpProReviewReason.LOW_RATING);
        when(flags.findById(flagId)).thenReturn(Optional.of(flag));

        service.dismissFlag(flagId, adminId);

        assertThat(flag.getDismissedAt()).isNotNull();
        assertThat(flag.getDismissedBy()).isEqualTo(adminId);
        verify(flags).save(flag);
        verify(audit).log(eq("GP_PRO_REVIEW_FLAG"), eq(flagId), eq("GP_PRO_REVIEW_DISMISSED"), eq(adminId), any());
    }

    @Test
    void dismissFlagIsIdempotentWhenAlreadyDismissed() {
        UUID flagId = UUID.randomUUID();
        GpProReviewFlagEntity flag = new GpProReviewFlagEntity();
        flag.setDismissedAt(LocalDateTime.now());
        when(flags.findById(flagId)).thenReturn(Optional.of(flag));

        service.dismissFlag(flagId, adminId);

        verify(flags, never()).save(any());
        verify(audit, never()).log(anyString(), any(), anyString(), any(), any());
    }

    @Test
    void dismissFlagRejectsResolvedAndUnknownFlags() {
        UUID flagId = UUID.randomUUID();
        GpProReviewFlagEntity resolved = new GpProReviewFlagEntity();
        resolved.setResolvedAt(LocalDateTime.now());
        when(flags.findById(flagId)).thenReturn(Optional.of(resolved));

        assertCode(() -> service.dismissFlag(flagId, adminId), HttpStatus.CONFLICT, "gp-pro-flag-resolved");
        assertCode(() -> service.dismissFlag(UUID.randomUUID(), adminId),
                HttpStatus.NOT_FOUND, "gp-pro-flag-not-found");
    }

    @Test
    void reviewQueueGroupsReasonsPerUser() {
        GpProReviewFlagEntity a = new GpProReviewFlagEntity();
        a.setUserId(userId);
        a.setReason(GpProReviewReason.OPEN_REPORT);
        a.setDetectedAt(LocalDateTime.of(2026, 10, 2, 4, 0));
        GpProReviewFlagEntity b = new GpProReviewFlagEntity();
        b.setUserId(userId);
        b.setReason(GpProReviewReason.LOW_RATING);
        b.setDetectedAt(LocalDateTime.of(2026, 10, 1, 4, 0));
        user.setFirstName("Awa");
        user.setLastName("Diallo");
        when(flags.findReviewUserIds(any())).thenReturn(
                new org.springframework.data.domain.PageImpl<>(List.of(userId)));
        when(users.findAllById(any())).thenReturn(List.of(user));
        when(flags.findByUserIdInAndResolvedAtIsNullAndDismissedAtIsNull(any())).thenReturn(List.of(a, b));

        var page = service.reviewQueue(0, 20);

        assertThat(page.getContent()).hasSize(1);
        var row = page.getContent().get(0);
        assertThat(row.userId()).isEqualTo(userId);
        assertThat(row.displayName()).isEqualTo("Awa Diallo");
        assertThat(row.reasons()).containsExactly(GpProReviewReason.LOW_RATING, GpProReviewReason.OPEN_REPORT);
        assertThat(row.detectedAt()).isEqualTo(LocalDateTime.of(2026, 10, 1, 4, 0));
    }

    @Test
    void reviewQueueEmpty() {
        when(flags.findReviewUserIds(any())).thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));
        assertThat(service.reviewQueue(0, 20).getContent()).isEmpty();
    }
}
