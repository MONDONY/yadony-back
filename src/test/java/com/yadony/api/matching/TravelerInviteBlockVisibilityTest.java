package com.yadony.api.matching;

import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.matching.dto.InviteRequest;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code POST /travelers/me/invite} — un voyageur sollicite directement un expéditeur pour
 * qu'il lui confie son colis. C'est le type même de sollicitation qu'un blocage doit faire
 * taire : l'invitation passe donc par la voie du dispatcher qui connaît l'émetteur.
 *
 * <p>Le statut HTTP reste 200 dans les deux cas, à dessein : un code distinct rendrait le
 * blocage détectable par le voyageur qui en fait l'objet.
 */
@ExtendWith(MockitoExtension.class)
class TravelerInviteBlockVisibilityTest {

    @Mock TravelerStatsService statsService;
    @Mock UserRepository userRepository;
    @Mock ProAnalyticsService analyticsService;
    @Mock AnnouncementRepository announcementRepository;
    @Mock MatchingService matchingService;
    @Mock PackageRequestRepository packageRequestRepository;
    @Mock NotificationDispatcher notificationDispatcher;
    @Mock BidService bidService;
    @Mock AnnouncementService announcementService;

    TravelerStatsController controller;

    static final String UID = "firebase-uid-traveler";
    final UUID travelerId = UUID.randomUUID();
    final UUID senderId = UUID.randomUUID();
    final UUID announcementId = UUID.randomUUID();
    final UUID requestId = UUID.randomUUID();

    @BeforeEach
    void setup() {
        controller = new TravelerStatsController(statsService, userRepository, analyticsService,
                announcementRepository, matchingService, packageRequestRepository,
                notificationDispatcher, bidService, announcementService);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        UID, null, List.of(new SimpleGrantedAuthority("ROLE_TRAVELER"))));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void stubHappyPath() {
        UserEntity traveler = new UserEntity();
        ReflectionTestUtils.setField(traveler, "id", travelerId);
        traveler.setRoles(Set.of(Role.TRAVELER));
        traveler.setProAccount(true);
        traveler.setFirstName("Ibrahima");
        when(userRepository.findByFirebaseUid(UID)).thenReturn(Optional.of(traveler));

        AnnouncementEntity announcement = new AnnouncementEntity();
        ReflectionTestUtils.setField(announcement, "id", announcementId);
        announcement.setTravelerId(travelerId);
        announcement.setDepartureCity("Paris");
        announcement.setArrivalCity("Dakar");
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));

        PackageRequestEntity request = new PackageRequestEntity();
        ReflectionTestUtils.setField(request, "id", requestId);
        request.setSenderId(senderId);
        request.setStatus(PackageRequestStatus.OPEN);
        when(packageRequestRepository.findById(requestId)).thenReturn(Optional.of(request));
    }

    @Test
    void invite_notMasked_notifiesSenderThroughBlockAwareChannel() {
        stubHappyPath();
        when(notificationDispatcher.notifyUnlessBlocked(
                eq(senderId), eq(travelerId), any(), any(), anyMap())).thenReturn(true);

        var response = controller.inviteSender(new InviteRequest(requestId, announcementId));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(notificationDispatcher).notifyUnlessBlocked(
                eq(senderId), eq(travelerId), contains("Invitation"), any(), anyMap());
        // La voie générique est aveugle au blocage : elle ne doit jamais servir ici.
        verify(notificationDispatcher, never()).notifyUser(any(), any(), any(), anyMap());
    }

    @Test
    void invite_masked_isSilentlyDroppedAndStillReturns200() {
        stubHappyPath();
        when(notificationDispatcher.notifyUnlessBlocked(
                eq(senderId), eq(travelerId), any(), any(), anyMap())).thenReturn(false);

        var response = controller.inviteSender(new InviteRequest(requestId, announcementId));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(notificationDispatcher, never()).notifyUser(any(), any(), any(), anyMap());
    }
}
