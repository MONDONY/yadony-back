package com.yadony.api.requests.service;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.requests.dto.PackageRequestResponse;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestInvitationEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.event.PackageRequestInvitationSentEvent;
import com.yadony.api.requests.repository.PackageRequestInvitationRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PackageRequestInsightServiceTest {

    @Mock private PackageRequestRepository requestRepository;
    @Mock private PackageRequestInvitationRepository invitationRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private PackageRequestInsightService service;

    private final UUID senderId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID requestId = UUID.randomUUID();
    private final UUID announcementId = UUID.randomUUID();
    private final LocalDate desiredDate = LocalDate.now().plusDays(30);

    @BeforeEach
    void setUp() {
        service = new PackageRequestInsightService(requestRepository, invitationRepository,
                announcementRepository, userRepository, auditService, eventPublisher);
    }

    private PackageRequestEntity request(PackageRequestStatus status) {
        PackageRequestEntity e = mock(PackageRequestEntity.class, withSettings().strictness(org.mockito.quality.Strictness.LENIENT));
        when(e.getId()).thenReturn(requestId);
        when(e.getSenderId()).thenReturn(senderId);
        when(e.getStatus()).thenReturn(status);
        when(e.getDepartureCity()).thenReturn("Divo");
        when(e.getArrivalCity()).thenReturn("Annemasse");
        when(e.getDesiredDate()).thenReturn(desiredDate);
        when(e.getDateToleranceDays()).thenReturn((short) 2);
        when(e.getViewCount()).thenReturn(14L);
        return e;
    }

    /** Trajet sur le corridor et dans la fenêtre de dates de {@link #request}. */
    private AnnouncementEntity trip(UUID owner, AnnouncementStatus status) {
        return trip(owner, status, "Divo", "Annemasse", desiredDate);
    }

    private AnnouncementEntity trip(UUID owner, AnnouncementStatus status,
                                     String departureCity, String arrivalCity, LocalDate departureDate) {
        AnnouncementEntity a = mock(AnnouncementEntity.class, withSettings().strictness(org.mockito.quality.Strictness.LENIENT));
        when(a.getId()).thenReturn(announcementId);
        when(a.getTravelerId()).thenReturn(owner);
        when(a.getStatus()).thenReturn(status);
        when(a.getDepartureCity()).thenReturn(departureCity);
        when(a.getArrivalCity()).thenReturn(arrivalCity);
        when(a.getDepartureDate()).thenReturn(departureDate);
        return a;
    }

    private PackageRequestResponse viewed(UUID owner, PackageRequestStatus status) {
        PackageRequestResponse r = mock(PackageRequestResponse.class, withSettings().strictness(org.mockito.quality.Strictness.LENIENT));
        when(r.id()).thenReturn(requestId);
        when(r.senderId()).thenReturn(owner);
        when(r.status()).thenReturn(status);
        return r;
    }

    // ─── recordView ──────────────────────────────────────────────────────────

    @Test
    void recordView_travelerOnOpenRequest_increments() {
        service.recordView(travelerId, viewed(senderId, PackageRequestStatus.OPEN));
        verify(requestRepository).incrementViewCount(requestId);
    }

    @Test
    void recordView_negotiatingRequest_increments() {
        service.recordView(travelerId, viewed(senderId, PackageRequestStatus.NEGOTIATING));
        verify(requestRepository).incrementViewCount(requestId);
    }

    @Test
    void recordView_owner_guest_orNonPublicStatus_doNotIncrement() {
        service.recordView(senderId, viewed(senderId, PackageRequestStatus.OPEN));
        service.recordView(null, viewed(senderId, PackageRequestStatus.OPEN));
        service.recordView(travelerId, viewed(senderId, PackageRequestStatus.ACCEPTED));
        verifyNoInteractions(requestRepository);
    }

    @Test
    void recordView_repositoryFailure_isSwallowed() {
        when(requestRepository.incrementViewCount(requestId)).thenThrow(new RuntimeException("db down"));
        service.recordView(travelerId, viewed(senderId, PackageRequestStatus.OPEN));
        verify(requestRepository).incrementViewCount(requestId);
    }

    // ─── getInsights ─────────────────────────────────────────────────────────

    @Test
    void getInsights_owner_returnsCountAndInvitedTrips() {
        PackageRequestEntity req = request(PackageRequestStatus.OPEN);
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(req));
        when(invitationRepository.findByPackageRequestIdOrderByCreatedAtAsc(requestId))
                .thenReturn(List.of(new PackageRequestInvitationEntity(requestId, announcementId, travelerId, senderId)));

        var insights = service.getInsights(senderId, requestId);

        assertThat(insights.viewCount()).isEqualTo(14L);
        assertThat(insights.invitedAnnouncementIds()).containsExactly(announcementId);
    }

    @Test
    void getInsights_notOwner_notFound() {
        PackageRequestEntity req = request(PackageRequestStatus.OPEN);
        when(requestRepository.findById(requestId)).thenReturn(Optional.of(req));

        assertThatThrownBy(() -> service.getInsights(travelerId, requestId))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getReason()).isEqualTo("request/not-found");
                });
    }

    @Test
    void getInsights_unknownRequest_notFound() {
        when(requestRepository.findById(requestId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getInsights(senderId, requestId))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getReason()).isEqualTo("request/not-found"));
    }

    // ─── invite ──────────────────────────────────────────────────────────────

    @Test
    void invite_newTrip_savesAuditsAndPublishes() {
        PackageRequestEntity req = request(PackageRequestStatus.OPEN);
        when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(req));
        when(invitationRepository.findByPackageRequestIdAndAnnouncementId(requestId, announcementId))
                .thenReturn(Optional.empty());
        AnnouncementEntity trip = trip(travelerId, AnnouncementStatus.ACTIVE);
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(trip));
        when(invitationRepository.countByPackageRequestId(requestId)).thenReturn(3L);
        when(invitationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        UserEntity sender = mock(UserEntity.class);
        when(sender.publicDisplayName()).thenReturn("Awa Koné");
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));

        var result = service.invite(senderId, requestId, announcementId);

        assertThat(result.created()).isTrue();
        assertThat(result.invitation().announcementId()).isEqualTo(announcementId);
        verify(auditService).log(eq("PACKAGE_REQUEST"), eq(requestId), eq("INVITATION_SENT"), eq(senderId),
                eq(Map.<String, Object>of("announcementId", announcementId.toString(), "travelerId", travelerId.toString())));
        ArgumentCaptor<PackageRequestInvitationSentEvent> captor =
                ArgumentCaptor.forClass(PackageRequestInvitationSentEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().travelerId()).isEqualTo(travelerId);
        assertThat(captor.getValue().senderName()).isEqualTo("Awa Koné");
        assertThat(captor.getValue().departureCity()).isEqualTo("Divo");
    }

    @Test
    void invite_alreadyInvited_returnsExistingWithoutPush() {
        PackageRequestEntity req = request(PackageRequestStatus.NEGOTIATING);
        when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(req));
        when(invitationRepository.findByPackageRequestIdAndAnnouncementId(requestId, announcementId))
                .thenReturn(Optional.of(new PackageRequestInvitationEntity(requestId, announcementId, travelerId, senderId)));

        var result = service.invite(senderId, requestId, announcementId);

        assertThat(result.created()).isFalse();
        verify(invitationRepository, never()).save(any());
        verifyNoInteractions(eventPublisher, auditService);
    }

    @Test
    void invite_notOwner_notFound() {
        PackageRequestEntity req = request(PackageRequestStatus.OPEN);
        when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(req));

        assertThatThrownBy(() -> service.invite(travelerId, requestId, announcementId))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getReason()).isEqualTo("request/not-found");
                });
    }

    @Test
    void invite_acceptedRequest_conflict() {
        PackageRequestEntity req = request(PackageRequestStatus.ACCEPTED);
        when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(req));

        assertThatThrownBy(() -> service.invite(senderId, requestId, announcementId))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getReason()).isEqualTo("request/not-invitable");
                });
    }

    @Test
    void invite_unknownTrip_notFound() {
        PackageRequestEntity req = request(PackageRequestStatus.OPEN);
        when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(req));
        when(invitationRepository.findByPackageRequestIdAndAnnouncementId(requestId, announcementId))
                .thenReturn(Optional.empty());
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.invite(senderId, requestId, announcementId))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getReason()).isEqualTo("announcement/not-found"));
    }

    @Test
    void invite_ownTrip_unprocessable() {
        PackageRequestEntity req = request(PackageRequestStatus.OPEN);
        when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(req));
        when(invitationRepository.findByPackageRequestIdAndAnnouncementId(requestId, announcementId))
                .thenReturn(Optional.empty());
        AnnouncementEntity ownTrip = trip(senderId, AnnouncementStatus.ACTIVE);
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ownTrip));

        assertThatThrownBy(() -> service.invite(senderId, requestId, announcementId))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getReason()).isEqualTo("invitation/own-trip");
                });
    }

    @Test
    void invite_inactiveTrip_unprocessable() {
        PackageRequestEntity req = request(PackageRequestStatus.OPEN);
        when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(req));
        when(invitationRepository.findByPackageRequestIdAndAnnouncementId(requestId, announcementId))
                .thenReturn(Optional.empty());
        AnnouncementEntity fullTrip = trip(travelerId, AnnouncementStatus.FULL);
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(fullTrip));

        assertThatThrownBy(() -> service.invite(senderId, requestId, announcementId))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getReason()).isEqualTo("invitation/trip-not-active"));
    }

    @Test
    void invite_differentDepartureCity_offCorridor() {
        PackageRequestEntity req = request(PackageRequestStatus.OPEN);
        when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(req));
        when(invitationRepository.findByPackageRequestIdAndAnnouncementId(requestId, announcementId))
                .thenReturn(Optional.empty());
        AnnouncementEntity offCorridorTrip = trip(travelerId, AnnouncementStatus.ACTIVE, "Bouaké", "Annemasse", desiredDate);
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(offCorridorTrip));

        assertThatThrownBy(() -> service.invite(senderId, requestId, announcementId))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getReason()).isEqualTo("invitation/off-corridor");
                });
        verify(invitationRepository, never()).save(any());
    }

    @Test
    void invite_differentArrivalCity_offCorridor() {
        PackageRequestEntity req = request(PackageRequestStatus.OPEN);
        when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(req));
        when(invitationRepository.findByPackageRequestIdAndAnnouncementId(requestId, announcementId))
                .thenReturn(Optional.empty());
        AnnouncementEntity offCorridorTrip = trip(travelerId, AnnouncementStatus.ACTIVE, "Divo", "Genève", desiredDate);
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(offCorridorTrip));

        assertThatThrownBy(() -> service.invite(senderId, requestId, announcementId))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getReason()).isEqualTo("invitation/off-corridor");
                });
        verify(invitationRepository, never()).save(any());
    }

    @Test
    void invite_cityCaseAndSpacingDifferences_accepted() {
        PackageRequestEntity req = request(PackageRequestStatus.OPEN);
        when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(req));
        when(invitationRepository.findByPackageRequestIdAndAnnouncementId(requestId, announcementId))
                .thenReturn(Optional.empty());
        AnnouncementEntity sameCorridorTrip = trip(travelerId, AnnouncementStatus.ACTIVE, "  DIVO ", " annemasse  ", desiredDate);
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(sameCorridorTrip));
        when(invitationRepository.countByPackageRequestId(requestId)).thenReturn(0L);
        when(invitationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        UserEntity sender = mock(UserEntity.class);
        when(sender.publicDisplayName()).thenReturn("Awa Koné");
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));

        var result = service.invite(senderId, requestId, announcementId);

        assertThat(result.created()).isTrue();
    }

    @Test
    void invite_tripDateOneDayOutsideWindow_offCorridor() {
        PackageRequestEntity req = request(PackageRequestStatus.OPEN);
        when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(req));
        when(invitationRepository.findByPackageRequestIdAndAnnouncementId(requestId, announcementId))
                .thenReturn(Optional.empty());
        AnnouncementEntity lateTrip = trip(travelerId, AnnouncementStatus.ACTIVE, "Divo", "Annemasse", desiredDate.plusDays(3));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(lateTrip));

        assertThatThrownBy(() -> service.invite(senderId, requestId, announcementId))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getReason()).isEqualTo("invitation/off-corridor");
                });
        verify(invitationRepository, never()).save(any());
    }

    @Test
    void invite_tripDateAtWindowEdge_accepted() {
        PackageRequestEntity req = request(PackageRequestStatus.OPEN);
        when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(req));
        when(invitationRepository.findByPackageRequestIdAndAnnouncementId(requestId, announcementId))
                .thenReturn(Optional.empty());
        AnnouncementEntity edgeTrip = trip(travelerId, AnnouncementStatus.ACTIVE, "Divo", "Annemasse", desiredDate.plusDays(2));
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(edgeTrip));
        when(invitationRepository.countByPackageRequestId(requestId)).thenReturn(0L);
        when(invitationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        UserEntity sender = mock(UserEntity.class);
        when(sender.publicDisplayName()).thenReturn("Awa Koné");
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));

        var result = service.invite(senderId, requestId, announcementId);

        assertThat(result.created()).isTrue();
    }

    @Test
    void invite_limitReached_unprocessable() {
        PackageRequestEntity req = request(PackageRequestStatus.OPEN);
        when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(req));
        when(invitationRepository.findByPackageRequestIdAndAnnouncementId(requestId, announcementId))
                .thenReturn(Optional.empty());
        AnnouncementEntity activeTrip = trip(travelerId, AnnouncementStatus.ACTIVE);
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(activeTrip));
        when(invitationRepository.countByPackageRequestId(requestId))
                .thenReturn((long) PackageRequestInsightService.MAX_INVITATIONS_PER_REQUEST);

        assertThatThrownBy(() -> service.invite(senderId, requestId, announcementId))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getReason()).isEqualTo("invitation/limit-reached"));
        verify(invitationRepository, never()).save(any());
    }
}
